// conv_fp_acc.c
// minimal bare-metal test program for RoCC floating-point convolution accelerator

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

// start floating-point convolution via custom instruction 2
static inline void conv_start_fp(void *kernel, unsigned long kernel_size)
{
    ROCC_INSTRUCTION_SS(0, (unsigned long)kernel, kernel_size, 2);
}

// poll status via custom instruction 3
static inline unsigned long conv_poll(void)
{
    unsigned long status;
    ROCC_INSTRUCTION_DS(0, status, 0, 3);
    return status;
}

// page-aligned memory buffers to avoid TLB page boundary faults
static float input[N][N] __attribute__((aligned(4096)));
static float kernel[K][K] __attribute__((aligned(64)));
static float output[N][N] __attribute__((aligned(4096)));

int main() {
    unsigned long start, end;

    // initialise input and pre-fault pages
    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++) {
            input[i][j] = 1.0f;
            output[i][j] = 0.0f;
        }

    // initialise kernel - 1/9 averaging
    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel[i][j] = 1.0f / 9.0f;

    // run hardware accelerator and measure cycles
    start = rdcycle();

    conv_set_input(input, output);
    conv_start_fp(kernel, K);

    unsigned long status;
    do {
        status = conv_poll();
    } while (!(status & 0x1)); // wait for completion bit

    end = rdcycle();

    // check for hardware errors
    if (status & 0x2) {
        printf("ERROR: accelerator reported error\n");
        return 1;
    }
    
    // print as scaled integers since %f not supported in bare-metal
    printf("corners:  [0][0]=%d [0][31]=%d [31][0]=%d [31][31]=%d\n",
        (int)(output[0][0]*1000), (int)(output[0][31]*1000),
        (int)(output[31][0]*1000), (int)(output[31][31]*1000));
    printf("edges:    [0][1]=%d [1][0]=%d [0][16]=%d [16][0]=%d\n",
        (int)(output[0][1]*1000), (int)(output[1][0]*1000),
        (int)(output[0][16]*1000), (int)(output[16][0]*1000));
    printf("centre:   [16][16]=%d [15][15]=%d\n",
        (int)(output[16][16]*1000), (int)(output[15][15]*1000));
    printf("Hardware FP convolution took %lu cycles\n", end - start);

    return 0;
}