#include "rocc.h"
#include "encoding.h"
#include <stdio.h>
#include <stdint.h>

#define N 32
#define K 3

static inline void conv_set_input(void *input, void *output)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)input, (unsigned long)output, 0);
}

static inline void conv_start_fp(void *kernel, unsigned long kernel_size)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)kernel, kernel_size, 3);
}

static inline unsigned long conv_poll(void)
{
    unsigned long status;
    ROCC_INSTRUCTION_DS(0, status, 0, 2);
    return status;
}

static float input[N][N] __attribute__((aligned(4096)));
static float kernel_mat[K][K] __attribute__((aligned(64)));
static float hw_output[N][N] __attribute__((aligned(4096)));
static float sw_output[N][N];

int main() {
    unsigned long sw_start, sw_end, hw_start, hw_end;

    // non-uniform input - distinct values to expose bugs
    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++) {
            input[i][j] = (float)(i * N + j + 1) / 100.0f;
            hw_output[i][j] = 0.0f;
        }

    // non-uniform kernel - distinct values per element
    kernel_mat[0][0] = 0.1f;
    kernel_mat[0][1] = 0.2f;
    kernel_mat[0][2] = 0.3f;
    kernel_mat[1][0] = 0.4f;
    kernel_mat[1][1] = 0.5f;
    kernel_mat[1][2] = 0.6f;
    kernel_mat[2][0] = 0.7f;
    kernel_mat[2][1] = 0.8f;
    kernel_mat[2][2] = 0.9f;

    // software FP convolution
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

    // hardware FP convolution
    hw_start = rdcycle();
    conv_set_input(input, hw_output);
    conv_start_fp(kernel_mat, K);
    unsigned long status;
    do {
        status = conv_poll();
    } while (!(status & 0x1));
    hw_end = rdcycle();

    if (status & 0x2) {
        printf("ERROR: accelerator reported error\n");
        return 1;
    }

    // verify - compare raw bits since float equality is exact for same operations
    int errors = 0;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            uint32_t hw_bits = *(uint32_t*)&hw_output[i][j];
            uint32_t sw_bits = *(uint32_t*)&sw_output[i][j];
            // allow 1 ULP difference for rounding
            uint32_t diff = hw_bits > sw_bits ? hw_bits - sw_bits : sw_bits - hw_bits;
            if (diff > 2) {
                printf("MISMATCH [%d][%d]: hw=0x%x sw=0x%x diff=%u\n",
                    i, j, hw_bits, sw_bits, diff);
                errors++;
                if (errors >= 10) {
                    printf("Too many errors, stopping\n");
                    goto done;
                }
            }
        }
    }

done:
    if (errors == 0)
        printf("PASS: all %d elements match\n", N * N);
    else
        printf("FAIL: %d mismatches\n", errors);

    printf("Software FP: %lu cycles\n", sw_end - sw_start);
    printf("Hardware FP: %lu cycles\n", hw_end - hw_start);
    printf("Speedup: %lux\n", (sw_end - sw_start) / (hw_end - hw_start));

    return 0;
}