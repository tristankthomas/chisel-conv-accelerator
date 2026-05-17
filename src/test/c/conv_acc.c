#include "rocc.h"
#include "encoding.h"
#include <stdio.h>
#include <stdint.h>

#define N 32
#define K 3

typedef uint16_t fixed88;
typedef uint32_t fixed88_wide;

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
    ROCC_INSTRUCTION_DS(0, status, 0, 2);
    return status;
}

// page aligned to avoid TLB page boundary faults
static fixed88 input[N][N] __attribute__((aligned(4096)));
static fixed88 kernel[K][K] __attribute__((aligned(64)));
static fixed88_wide output[N][N] __attribute__((aligned(4096)));

int main() {
    unsigned long start, end;

    // initialise input and pre-fault pages
    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++) {
            input[i][j] = 256;
            output[i][j] = 0;
        }

    // initialise kernel
    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel[i][j] = 28;

    printf("input addr: %lx\n", (unsigned long)input);
    printf("output addr: %lx\n", (unsigned long)output);
    printf("kernel addr: %lx\n", (unsigned long)kernel);

    start = rdcycle();

    conv_set_input(input, output);
    conv_start(kernel, K);

    unsigned long status;
    do {
        status = conv_poll();
    } while (!(status & 0x1));

    end = rdcycle();

    if (status & 0x2) {
        printf("ERROR: accelerator reported error\n");
        return 1;
    }

    printf("output[0][0] = %u\n", output[0][0]);
    printf("output[0][1] = %u\n", output[0][1]);
    printf("output[1][0] = %u\n", output[1][0]);
    printf("output[1][1] = %u\n", output[1][1]);
    printf("output[16][16] = %u\n", output[16][16]);
    printf("Hardware convolution took %lu cycles\n", end - start);

    return 0;
}