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

static inline void conv_start(void *kernel, unsigned long kernel_size)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)kernel, kernel_size, 1);
}

static inline unsigned long conv_poll(void)
{
    unsigned long status;
    ROCC_INSTRUCTION_DS(0, status, 0, 3);
    return status;
}

// simple LCG pseudo-random generator
static uint32_t prng_state = 12533;
static uint16_t prng_next(void) {
    prng_state = prng_state * 1664525 + 1013904223;
    return (uint16_t)((prng_state >> 16) & 0xFF) + 1;
}

static uint16_t input[N][N] __attribute__((aligned(4096)));
static uint16_t kernel_mat[K][K] __attribute__((aligned(64)));
static uint16_t hw_output[N][N] __attribute__((aligned(4096)));
static uint16_t sw_output[N][N];

int main() {
    unsigned long sw_start, sw_end, hw_start, hw_end;

    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++) {
            input[i][j] = prng_next();
            hw_output[i][j] = 0;
        }

    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel_mat[i][j] = (prng_next() & 0x1F) + 1;

    printf("Testing K=%d kernel\n", K);

    // hardware convolution runs FIRST while TLB is hot
    hw_start = rdcycle();
    conv_set_input(input, hw_output);
    conv_start(kernel_mat, K);
    unsigned long status;
    do { status = conv_poll(); } while (!(status & 0x1));
    hw_end = rdcycle();

    // software convolution runs SECOND
    sw_start = rdcycle();
    int pad = K / 2;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            uint64_t acc = 0;
            for (int ki = 0; ki < K; ki++) {
                for (int kj = 0; kj < K; kj++) {
                    int ii = i + ki - pad;
                    int jj = j + kj - pad;
                    if (ii >= 0 && ii < N && jj >= 0 && jj < N)
                        acc += (uint32_t)input[ii][jj] * (uint32_t)kernel_mat[ki][kj];
                }
            }
            sw_output[i][j] = (uint16_t)(acc >> 8);
        }
    }
    sw_end = rdcycle();

    if (status & 0x2) {
        printf("ERROR: accelerator reported error\n");
        return 1;
    }

    int errors = 0;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            if (hw_output[i][j] != sw_output[i][j]) {
                printf("MISMATCH [%d][%d]: hw=%u sw=%u\n", i, j, hw_output[i][j], sw_output[i][j]);
                if (++errors >= 10) { printf("Too many errors, stopping\n"); goto done; }
            }
        }
    }

done:
    if (errors == 0) printf("PASS: all %d elements match\n", N * N);
    else printf("FAIL: %d mismatches\n", errors);

    printf("Software: %lu cycles\n", sw_end - sw_start);
    printf("Hardware: %lu cycles\n", hw_end - hw_start);
    printf("Speedup: %lux\n", (sw_end - sw_start) / (hw_end - hw_start));

    return 0;
}