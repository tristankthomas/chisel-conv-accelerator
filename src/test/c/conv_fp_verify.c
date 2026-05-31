#include "rocc.h"
#include "encoding.h"
#include <stdio.h>
#include <stdint.h>

#define N 32
#define K 5

static inline void conv_set_input(void *input, void *output)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)input, (unsigned long)output, 0);
}

static inline void conv_start_fp(void *kernel, unsigned long kernel_size)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)kernel, kernel_size, 2);
}

static inline unsigned long conv_poll(void)
{
    unsigned long status;
    ROCC_INSTRUCTION_DS(0, status, 0, 3);
    return status;
}

// simple LCG pseudo-random generator
static uint32_t prng_state = 12532;
static float prng_float(void) {
    prng_state = prng_state * 1664525 + 1013904223;
    // generate float in range 0.01 .. 2.56
    return (float)((prng_state >> 16) & 0xFF) / 100.0f + 0.01f;
}

static float input[N][N] __attribute__((aligned(4096)));
static float kernel_mat[K][K] __attribute__((aligned(64)));
static float hw_output[N][N] __attribute__((aligned(4096)));
static float sw_output[N][N];

int main() {
    unsigned long sw_start, sw_end, hw_start, hw_end;

    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++) {
            input[i][j] = prng_float();
            hw_output[i][j] = 0.0f;
        }

    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel_mat[i][j] = prng_float();

    printf("Testing FP K=%d kernel\n", K);

    hw_start = rdcycle();
    conv_set_input(input, hw_output);
    conv_start_fp(kernel_mat, K);
    unsigned long status;
    do { status = conv_poll(); } while (!(status & 0x1));
    hw_end = rdcycle();

    if (status & 0x2) {
        printf("ERROR: accelerator reported error\n");
        return 1;
    }

    sw_start = rdcycle();
    int pad = K / 2;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            float acc = 0.0f;
            for (int ki = 0; ki < K; ki++) {
                for (int kj = 0; kj < K; kj++) {
                    int ii = i + ki - pad;
                    int jj = j + kj - pad;
                    if (ii >= 0 && ii < N && jj >= 0 && jj < N)
                        acc += input[ii][jj] * kernel_mat[ki][kj];
                }
            }
            sw_output[i][j] = acc;
        }
    }
    sw_end = rdcycle();

    int errors = 0;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            uint32_t hw_bits = *(uint32_t*)&hw_output[i][j];
            uint32_t sw_bits = *(uint32_t*)&sw_output[i][j];
            uint32_t diff = hw_bits > sw_bits ? hw_bits - sw_bits : sw_bits - hw_bits;
            if (diff > 4) {
                printf("MISMATCH [%d][%d]: hw=0x%x sw=0x%x diff=%u\n",
                    i, j, hw_bits, sw_bits, diff);
                if (++errors >= 10) { printf("Too many errors, stopping\n"); goto done; }
            }
        }
    }

done:
    if (errors == 0) printf("PASS: all %d elements match\n", N * N);
    else printf("FAIL: %d mismatches\n", errors);

    printf("Software FP: %lu cycles\n", sw_end - sw_start);
    printf("Hardware FP: %lu cycles\n", hw_end - hw_start);
    printf("Speedup: %lux\n", (sw_end - sw_start) / (hw_end - hw_start));

    return 0;
}