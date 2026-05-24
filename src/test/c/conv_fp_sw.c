#include "encoding.h"
#include <stdio.h>
#include <stdint.h>

#define N 32
#define K 3

int main() {
    unsigned long start, end;

    // input matrix - all 1.0
    static float input[N][N];
    // 3x3 averaging kernel - each element = 1/9
    static float kernel[K][K];
    static float output[N][N];

    // initialise input
    for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++)
            input[i][j] = 1.0f;

    // initialise kernel
    for (int i = 0; i < K; i++)
        for (int j = 0; j < K; j++)
            kernel[i][j] = 1.0f / 9.0f;

    start = rdcycle();

    int pad = K / 2;
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            float acc = 0.0f;
            for (int ki = 0; ki < K; ki++) {
                for (int kj = 0; kj < K; kj++) {
                    int ii = i + ki - pad;
                    int jj = j + kj - pad;
                    if (ii >= 0 && ii < N && jj >= 0 && jj < N) {
                        acc += input[ii][jj] * kernel[ki][kj];
                    }
                }
            }
            output[i][j] = acc;
        }
    }

    end = rdcycle();

    // print as scaled integers (x1000) since %f not supported in bare-metal
    printf("corners:  [0][0]=%d [0][31]=%d [31][0]=%d [31][31]=%d\n",
        (int)(output[0][0]*1000), (int)(output[0][31]*1000),
        (int)(output[31][0]*1000), (int)(output[31][31]*1000));
    printf("edges:    [0][1]=%d [1][0]=%d [0][16]=%d [16][0]=%d\n",
        (int)(output[0][1]*1000), (int)(output[1][0]*1000),
        (int)(output[0][16]*1000), (int)(output[16][0]*1000));
    printf("centre:   [16][16]=%d [15][15]=%d\n",
        (int)(output[16][16]*1000), (int)(output[15][15]*1000));
    printf("Software FP convolution took %lu cycles\n", end - start);

    return 0;
}