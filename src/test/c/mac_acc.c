#include "rocc.h"
#include "encoding.h"
#include <stdio.h>

static inline void mac_write(unsigned long data_1, unsigned long data_2)
{
    ROCC_INSTRUCTION_SS(0, data_1, data_2, 0);
}

static inline unsigned long mac_compute(void)
{
    unsigned long value;
    ROCC_INSTRUCTION_DS(0, value, 0, 1);
    return value;
}

int main() {
    unsigned long start, end;

    // initialising matrices
    int matA[2][2] = {{1, 2}, {3, 4}};
    int matB[2][2] = {{5, 6}, {7, 8}};
    int result[2][2] = {0};

    start = rdcycle();

    // Iterate through rows of A and columns of B
    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            // Load row elements of A (a0, a1)
            mac_write(matA[i][0], matA[i][1]);
            
            // Load column elements of B (b0, b1)
            mac_write(matB[0][j], matB[1][j]);
            
            // Compute hardware MAC and store
            result[i][j] = mac_compute();
        }
    }

    end = rdcycle();

    printf("Result C[0][0]: %d\n", result[0][0]);
    printf("Result C[0][1]: %d\n", result[0][1]);
    printf("Result C[1][0]: %d\n", result[1][0]);
    printf("Result C[1][1]: %d\n", result[1][1]);
    printf("Hardware MAC execution took %lu cycles\n", end - start);

    return 0;
}