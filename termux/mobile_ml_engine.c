#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <signal.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

typedef struct {
    int id;
    int hidden;
    int ff;
    uint64_t end_ns;
    volatile int *stop;
    double ops;
    uint64_t steps;
    double loss_sum;
} WorkerCfg;

static volatile sig_atomic_t g_stop = 0;

static void signal_handler(int sig)
{
    (void)sig;
    g_stop = 1;
}

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL +
           (uint64_t)ts.tv_nsec;
}

static uint64_t read_mem_available_kb(void)
{
    FILE *f = fopen("/proc/meminfo", "r");

    if (!f)
        return 0;

    char key[64];
    char unit[32];
    unsigned long long value;
    uint64_t result = 0;

    while (fscanf(f, "%63s %llu %31s", key, &value, unit) == 3)
    {
        if (strcmp(key, "MemAvailable:") == 0)
        {
            result = value;
            break;
        }
    }

    fclose(f);
    return result;
}

static uint64_t rng64(uint64_t *state)
{
    uint64_t x = *state;

    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;

    *state = x;

    return x;
}

static float random_float(uint64_t *state)
{
    uint64_t value = rng64(state);

    return ((float)(value & 0x00ffffff) /
            8388608.0f) - 1.0f;
}

static float dot_product(
    const float *a,
    const float *b,
    int n)
{

#if defined(__aarch64__)

    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    float32x4_t acc2 = vdupq_n_f32(0.0f);
    float32x4_t acc3 = vdupq_n_f32(0.0f);

    int i = 0;

    for (; i + 16 <= n; i += 16)
    {
        float32x4_t a0 = vld1q_f32(a + i);
        float32x4_t a1 = vld1q_f32(a + i + 4);
        float32x4_t a2 = vld1q_f32(a + i + 8);
        float32x4_t a3 = vld1q_f32(a + i + 12);

        float32x4_t b0 = vld1q_f32(b + i);
        float32x4_t b1 = vld1q_f32(b + i + 4);
        float32x4_t b2 = vld1q_f32(b + i + 8);
        float32x4_t b3 = vld1q_f32(b + i + 12);

        acc0 = vfmaq_f32(acc0, a0, b0);
        acc1 = vfmaq_f32(acc1, a1, b1);
        acc2 = vfmaq_f32(acc2, a2, b2);
        acc3 = vfmaq_f32(acc3, a3, b3);
    }

    acc0 = vaddq_f32(acc0, acc1);
    acc2 = vaddq_f32(acc2, acc3);
    acc0 = vaddq_f32(acc0, acc2);

    float32x2_t low = vget_low_f32(acc0);
    float32x2_t high = vget_high_f32(acc0);

    float32x2_t pair = vadd_f32(low, high);
    pair = vpadd_f32(pair, pair);

    float result = vget_lane_f32(pair, 0);

    for (; i < n; i++)
        result += a[i] * b[i];

    return result;

#else

    float result = 0.0f;

    for (int i = 0; i < n; i++)
        result += a[i] * b[i];

    return result;

#endif
}

static float fast_gelu(float x)
{
    const float c = 0.7978845608f;

    float x3 = x * x * x;

    return 0.5f * x *
           (1.0f +
            tanhf(c *
                  (x +
                   0.044715f * x3)));
}

static void layer_norm(
    float *x,
    int n)
{
    double mean = 0.0;
    double sq = 0.0;

    for (int i = 0; i < n; i++)
    {
        mean += x[i];
        sq += (double)x[i] *
              (double)x[i];
    }

    mean /= n;

    double variance =
        sq / n -
        mean * mean;

    float inv =
        1.0f /
        sqrtf(
            (float)variance +
            1e-5f);

    for (int i = 0; i < n; i++)
    {
        x[i] =
            (x[i] -
             (float)mean) *
            inv;
    }
}

static void softmax_block(
    float *x,
    int n)
{
    float maximum = x[0];

    for (int i = 1; i < n; i++)
    {
        if (x[i] > maximum)
            maximum = x[i];
    }

    double total = 0.0;

    for (int i = 0; i < n; i++)
    {
        x[i] =
            expf(
                x[i] -
                maximum);

        total += x[i];
    }

    float inv =
        1.0f /
        (float)total;

    for (int i = 0; i < n; i++)
        x[i] *= inv;
}

static void *ml_worker(
    void *argument)
{
    WorkerCfg *cfg =
        (WorkerCfg *)argument;

    int H = cfg->hidden;
    int F = cfg->ff;

    uint64_t state =
        0x9e3779b97f4a7c15ULL ^
        ((uint64_t)cfg->id + 1ULL) *
        0xbf58476d1ce4e5b9ULL;

    float *input =
        aligned_alloc(
            64,
            (size_t)H *
            sizeof(float));

    float *hidden =
        aligned_alloc(
            64,
            (size_t)F *
            sizeof(float));

    float *output =
        aligned_alloc(
            64,
            (size_t)H *
            sizeof(float));

    float *target =
        aligned_alloc(
            64,
            (size_t)H *
            sizeof(float));

    float *momentum =
        aligned_alloc(
            64,
            (size_t)H *
            sizeof(float));

    float *w1 =
        aligned_alloc(
            64,
            (size_t)F *
            H *
            sizeof(float));

    float *w2 =
        aligned_alloc(
            64,
            (size_t)H *
            F *
            sizeof(float));

    if (!input ||
        !hidden ||
        !output ||
        !target ||
        !momentum ||
        !w1 ||
        !w2)
    {
        fprintf(
            stderr,
            "worker %d allocation failure\n",
            cfg->id);

        free(input);
        free(hidden);
        free(output);
        free(target);
        free(momentum);
        free(w1);
        free(w2);

        return NULL;
    }

    for (int i = 0; i < H; i++)
    {
        input[i] =
            random_float(&state);

        target[i] =
            random_float(&state);

        momentum[i] = 0.0f;
    }

    for (size_t i = 0;
         i < (size_t)F * H;
         i++)
    {
        w1[i] =
            random_float(&state) *
            0.035f;
    }

    for (size_t i = 0;
         i < (size_t)H * F;
         i++)
    {
        w2[i] =
            random_float(&state) *
            0.035f;
    }

    const float learning_rate =
        0.00001f;

    const float beta =
        0.92f;

    double operations_per_step =
        6.0 *
        (double)H *
        (double)F;

    while (!g_stop &&
           !*(cfg->stop) &&
           now_ns() <
               cfg->end_ns)
    {
        layer_norm(
            input,
            H);

        for (int row = 0;
             row < F;
             row++)
        {
            float value =
                dot_product(
                    w1 +
                    (size_t)row *
                    H,
                    input,
                    H);

            hidden[row] =
                fast_gelu(
                    value);
        }

        for (int row = 0;
             row < H;
             row++)
        {
            output[row] =
                dot_product(
                    w2 +
                    (size_t)row *
                    F,
                    hidden,
                    F);
        }

        softmax_block(
            output,
            H);

        double loss = 0.0;

        for (int row = 0;
             row < H;
             row++)
        {
            float error =
                output[row] -
                target[row];

            loss +=
                (double)error *
                error;

            momentum[row] =
                beta *
                momentum[row] +
                (1.0f - beta) *
                error;

            float gradient =
                2.0f *
                momentum[row] /
                H;

            float *weights =
                w2 +
                (size_t)row *
                F;

            for (int col = 0;
                 col < F;
                 col++)
            {
                weights[col] -=
                    learning_rate *
                    gradient *
                    hidden[col];
            }

            input[row] =
                0.993f *
                input[row] +
                0.007f *
                target[row];
        }

        if ((cfg->steps & 31ULL) == 0)
        {
            for (int i = 0;
                 i < H;
                 i++)
            {
                target[i] =
                    0.995f *
                    target[i] +
                    0.005f *
                    random_float(
                        &state);
            }
        }

        cfg->loss_sum += loss;
        cfg->steps++;
        cfg->ops +=
            operations_per_step;
    }

    free(input);
    free(hidden);
    free(output);
    free(target);
    free(momentum);
    free(w1);
    free(w2);

    return NULL;
}

static double read_max_temperature(void)
{
    double maximum = -1.0;
    char path[256];
    char type_path[256];
    char type_buf[64];

    for (int zone = 0; zone < 128; zone++)
    {
        snprintf(type_path, sizeof(type_path), "/sys/class/thermal/thermal_zone%d/type", zone);
        FILE *tf = fopen(type_path, "r");
        if (tf)
        {
            if (fgets(type_buf, sizeof(type_buf), tf))
            {
                if (strstr(type_buf, "trip") ||
                    strstr(type_buf, "bcl") ||
                    strstr(type_buf, "threshold") ||
                    strstr(type_buf, "lvl"))
                {
                    fclose(tf);
                    continue;
                }
            }
            fclose(tf);
        }

        snprintf(path, sizeof(path), "/sys/class/thermal/thermal_zone%d/temp", zone);
        FILE *f = fopen(path, "r");
        if (!f)
            continue;

        double value;
        if (fscanf(f, "%lf", &value) == 1)
        {
            if (value > 1000.0)
                value /= 1000.0;

            if (value >= 20.0 &&
                value <= 85.0 &&
                value > maximum)
            {
                maximum = value;
            }
        }
        fclose(f);
    }

    FILE *battery = fopen("/sys/class/power_supply/battery/temp", "r");
    if (battery)
    {
        double value;
        if (fscanf(battery, "%lf", &value) == 1)
        {
            if (value > 1000.0)
                value /= 1000.0;
            else if (value > 100.0)
                value /= 10.0;

            if (value >= 20.0 &&
                value <= 85.0 &&
                value > maximum)
            {
                maximum = value;
            }
        }
        fclose(battery);
    }

    return maximum;
}

static void memory_bandwidth_pass(
    uint8_t *memory,
    size_t bytes,
    uint64_t *state)
{
    if (!memory ||
        bytes < 4096)
        return;

    uint8_t acc =
        (uint8_t)(*state);

    const size_t page = 4096;

    for (size_t i = 0;
         i < bytes;
         i += page)
    {
        uint8_t value =
            memory[i];

        acc ^=
            value +
            (uint8_t)(i >> 12);

        memory[i] =
            (uint8_t)(
                value +
                acc +
                1);
    }

    *state =
        *state *
        6364136223846793005ULL +
        acc +
        1ULL;
}

int main(void)
{
    signal(
        SIGINT,
        signal_handler);

    signal(
        SIGTERM,
        signal_handler);

    long cpu_count =
        sysconf(
            _SC_NPROCESSORS_ONLN);

    if (cpu_count < 1)
        cpu_count = 1;

    int threads =
        (int)cpu_count;

    char *environment =
        getenv("CPU_THREADS");

    if (environment &&
        atoi(environment) > 0)
    {
        threads =
            atoi(environment);
    }

    if (threads > 64)
        threads = 64;

    int duration_seconds =
        1200;

    environment =
        getenv("DURATION_SEC");

    if (environment &&
        atoi(environment) > 0)
    {
        duration_seconds =
            atoi(environment);
    }

    int ram_percentage =
        65;

    environment =
        getenv("RAM_PCT");

    if (environment)
    {
        int requested =
            atoi(environment);

        if (requested >= 10 &&
            requested <= 80)
        {
            ram_percentage =
                requested;
        }
    }

    uint64_t available_bytes =
        read_mem_available_kb() *
        1024ULL;

    size_t requested_ram =
        (size_t)(
            (long double)
            available_bytes *
            ram_percentage /
            100.0L);

    size_t maximum_ram =
        (size_t)6 *
        1024ULL *
        1024ULL *
        1024ULL;

    if (requested_ram >
        maximum_ram)
    {
        requested_ram =
            maximum_ram;
    }

    uint8_t *memory_arena =
        NULL;

    size_t allocated_ram =
        requested_ram;

    while (
        allocated_ram >=
        128ULL *
        1024ULL *
        1024ULL)
    {
        memory_arena =
            (uint8_t *)
            malloc(
                allocated_ram);

        if (memory_arena)
            break;

        allocated_ram =
            (size_t)(
                allocated_ram *
                0.82);
    }

    if (memory_arena)
    {
        for (size_t i = 0;
             i < allocated_ram;
             i += 4096)
        {
            memory_arena[i] =
                (uint8_t)(
                    (i >> 12) ^
                    (i >> 20));
        }
    }

    int hidden_size =
        256;

    int feedforward_size =
        1024;

    pthread_t *thread_handles =
        calloc(
            threads,
            sizeof(
                pthread_t));

    WorkerCfg *workers =
        calloc(
            threads,
            sizeof(
                WorkerCfg));

    if (!thread_handles ||
        !workers)
    {
        fprintf(
            stderr,
            "thread allocation failed\n");

        free(memory_arena);
        free(thread_handles);
        free(workers);

        return 1;
    }

    volatile int stop = 0;

    uint64_t start_time =
        now_ns();

    uint64_t end_time =
        start_time +
        (uint64_t)
        duration_seconds *
        1000000000ULL;

    printf(
        "\nMOBILE ML COMPUTE ENGINE\n");

    printf(
        "architecture      : ");

#if defined(__aarch64__)
    printf(
        "ARM64 NEON\n");
#else
    printf(
        "generic\n");
#endif

    printf(
        "logical CPUs      : %ld\n",
        cpu_count);

    printf(
        "worker threads    : %d\n",
        threads);

    printf(
        "runtime           : %d seconds\n",
        duration_seconds);

    printf(
        "RAM policy        : %d%% MemAvailable\n",
        ram_percentage);

    printf(
        "RAM committed     : %.2f GiB\n",
        (double)
        allocated_ram /
        1073741824.0);

    printf(
        "network width     : %d -> %d -> %d\n",
        hidden_size,
        feedforward_size,
        hidden_size);

    printf(
        "\n");

    fflush(stdout);

    for (int i = 0;
         i < threads;
         i++)
    {
        workers[i].id =
            i;

        workers[i].hidden =
            hidden_size;

        workers[i].ff =
            feedforward_size;

        workers[i].end_ns =
            end_time;

        workers[i].stop =
            &stop;

        workers[i].ops =
            0.0;

        workers[i].steps =
            0;

        workers[i].loss_sum =
            0.0;

        pthread_create(
            &thread_handles[i],
            NULL,
            ml_worker,
            &workers[i]);
    }

    uint64_t memory_state =
        123456789ULL;

    uint64_t last_memory_pass =
        start_time;

    double peak_throughput =
        0.0;

    while (!g_stop &&
           now_ns() <
               end_time)
    {
        sleep(5);

        uint64_t current_time =
            now_ns();

        double elapsed =
            (current_time -
             start_time) /
            1e9;

        double total_ops =
            0.0;

        double total_loss =
            0.0;

        uint64_t total_steps =
            0;

        for (int i = 0;
             i < threads;
             i++)
        {
            total_ops +=
                workers[i].ops;

            total_steps +=
                workers[i].steps;

            total_loss +=
                workers[i].
                loss_sum;
        }

        double gflops =
            total_ops /
            elapsed /
            1e9;

        if (gflops >
            peak_throughput)
        {
            peak_throughput =
                gflops;
        }

        uint64_t mem_available =
            read_mem_available_kb();

        double temperature =
            read_max_temperature();

        double progress =
            elapsed /
            duration_seconds *
            100.0;

        if (progress > 100.0)
            progress = 100.0;

        printf(
            "time=%6.1fs "
            "progress=%5.1f%% "
            "steps=%llu "
            "throughput=%8.2f GFLOP/s "
            "peak=%8.2f "
            "ram_free=%5.2f GiB",
            elapsed,
            progress,
            (unsigned long long)
            total_steps,
            gflops,
            peak_throughput,
            (double)
            mem_available *
            1024.0 /
            1073741824.0);

        if (temperature > 0)
        {
            printf(
                " temp=%5.1fC",
                temperature);
        }

        if (total_steps > 0)
        {
            printf(
                " loss=%8.5f",
                total_loss /
                total_steps);
        }

        printf("\n");

        fflush(stdout);

        if (memory_arena &&
            current_time -
            last_memory_pass >
            10000000000ULL)
        {
            memory_bandwidth_pass(
                memory_arena,
                allocated_ram,
                &memory_state);

            last_memory_pass =
                current_time;
        }

        if (temperature >= 88.0)
        {
            printf(
                "thermal guard active: %.1fC\n",
                temperature);

            fflush(stdout);

            sleep(20);
        }
    }

    stop = 1;

    for (int i = 0;
         i < threads;
         i++)
    {
        pthread_join(
            thread_handles[i],
            NULL);
    }

    double elapsed =
        (now_ns() -
         start_time) /
        1e9;

    double total_ops =
        0.0;

    double total_loss =
        0.0;

    uint64_t total_steps =
        0;

    for (int i = 0;
         i < threads;
         i++)
    {
        total_ops +=
            workers[i].ops;

        total_steps +=
            workers[i].steps;

        total_loss +=
            workers[i].
            loss_sum;
    }

    double average_gflops =
        total_ops /
        elapsed /
        1e9;

    printf(
        "\nFINAL COMPUTE REPORT\n");

    printf(
        "elapsed           : %.2f sec\n",
        elapsed);

    printf(
        "training steps    : %llu\n",
        (unsigned long long)
        total_steps);

    printf(
        "compute volume    : %.3f TFLOP-equivalent\n",
        total_ops /
        1e12);

    printf(
        "average throughput: %.2f GFLOP/s\n",
        average_gflops);

    printf(
        "peak throughput   : %.2f GFLOP/s\n",
        peak_throughput);

    if (total_steps > 0)
    {
        printf(
            "average loss     : %.6f\n",
            total_loss /
            total_steps);
    }

    printf(
        "RAM allocation    : %.2f GiB\n",
        (double)
        allocated_ram /
        1073741824.0);

    printf(
        "worker count      : %d\n",
        threads);

#if defined(__aarch64__)
    printf(
        "vector backend    : ARM NEON\n");
#else
    printf(
        "vector backend    : scalar\n");
#endif

    free(
        memory_arena);

    free(
        thread_handles);

    free(
        workers);

    return 0;
}
