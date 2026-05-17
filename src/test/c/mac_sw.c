#include "encoding.h"
#include <stdio.h>


int main() {
    unsigned long start, end;

    // initialising matrices
    volatile int matA[2][2] = {{1, 2}, {3, 4}};
    volatile int matB[2][2] = {{5, 6}, {7, 8}};
    int result[2][2] = {0};

    start = rdcycle();

    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            for (int k = 0; k < 2; k++) {
                result[i][j] += matA[i][k] * matB[k][j];
            }
        }
    }

    end = rdcycle();

    printf("Result: %d\n", result[0][0]);
    printf("Result C[0][1]: %d\n", result[0][1]);
    printf("Result C[1][0]: %d\n", result[1][0]);
    printf("Result C[1][1]: %d\n", result[1][1]);
    printf("My matrix multiplication execution took %lu cycles\n", end-start);

    return 0;
}