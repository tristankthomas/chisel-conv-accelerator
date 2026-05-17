#include "encoding.h"
#include <stdio.h>
#include <stdint.h>

#define N 32
#define K 3

// 8.8 fixed point: 1.0 = 256
typedef uint16_t fixed88;
typedef uint32_t fixed88_wide;

int main() {
    unsigned long start, end;

    // input matrix - all 1.0 (256 in 8.8)
    static fixed88 input[N][N];
    // 3x3 averaging kernel - each element = 1/9 ≈ 0.111 = 28 in 8.8
    static fixed88 kernel[K][K];
    static fixed88_wide output[N][N];

    // initialise input
    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++)
            input[i][j] = 256;  // 1.0 in 8.8

    // initialise kernel
    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel[i][j] = 28;  // ~1/9 in 8.8

    start = rdcycle();

    int pad = K / 2;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            uint64_t acc = 0;
            for (int ki = 0; ki < K; ki++) {
                for (int kj = 0; kj < K; kj++) {
                    int ii = i + ki - pad;
                    int jj = j + kj - pad;
                    if (ii >= 0 && ii < N && jj >= 0 && jj < N) {
                        acc += (uint32_t)input[ii][jj] * (uint32_t)kernel[ki][kj];
                    }
                }
            }
            output[i][j] = (fixed88_wide)(acc >> 8);
        }
    }

    end = rdcycle();

    printf("output[0][0] = %u\n", output[0][0]);
    printf("output[16][16] = %u\n", output[16][16]);
    printf("Software convolution took %lu cycles\n", end - start);

    return 0;
}