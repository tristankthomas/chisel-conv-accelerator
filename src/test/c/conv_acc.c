// conv_acc.c
// minimal bare-metal test program for RoCC fixed-point convolution accelerator

#include "rocc.h"
#include "encoding.h"
#include <stdio.h>
#include <stdint.h>

// matrix and kernel dimensions
#define N 32
#define K 3

// configure input and output memory addresses via custom instruction 0
static inline void conv_set_input(void *input, void *output)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)input, (unsigned long)output, 0);
}

// start fixed-point convolution via custom instruction 1
static inline void conv_start(void *kernel, unsigned long kernel_size)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)kernel, kernel_size, 1);
}

// poll status via custom instruction 3
static inline unsigned long conv_poll(void)
{
    unsigned long status;
    ROCC_INSTRUCTION_DS(0, status, 0, 3);
    return status;
}

// page-aligned memory buffers to avoid TLB page boundary faults
static uint16_t input[N][N] __attribute__((aligned(4096)));
static uint16_t kernel[K][K] __attribute__((aligned(64)));
static uint16_t output[N][N] __attribute__((aligned(4096)));

int main() {
    unsigned long start, end;

    // initialise input and pre-fault pages
    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++) {
            input[i][j] = 256;
            output[i][j] = 0;
        }

    // initialise kernel weights
    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel[i][j] = 28;

    // run hardware accelerator and measure cycles
    start = rdcycle();

    conv_set_input(input, output);
    conv_start(kernel, K);

    unsigned long status;
    unsigned long timeout = 10000;
    // poll until completion bit is set or timeout occurs
    do {
        status = conv_poll();
        timeout--;
        if (timeout == 0) {
            printf("TIMEOUT: accelerator did not complete\n");
            return 1;
        }
    } while (!(status & 0x1));

    end = rdcycle();

    // check for hardware errors
    if (status & 0x2) {
        printf("ERROR: accelerator reported error\n");
        return 1;
    }

    // print sample outputs to verify boundary conditions and computation
    printf("corners:  [0][0]=%u [0][31]=%u [31][0]=%u [31][31]=%u\n", 
        output[0][0], output[0][31], output[31][0], output[31][31]);
    printf("edges:    [0][1]=%u [1][0]=%u [0][16]=%u [16][0]=%u\n",
        output[0][1], output[1][0], output[0][16], output[16][0]);
    printf("centre:   [16][16]=%u [15][15]=%u\n",
        output[16][16], output[15][15]);
    printf("Hardware convolution took %lu cycles\n", end - start);

    return 0;
}