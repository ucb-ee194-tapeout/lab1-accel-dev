#include "../../../tests/rocc.h"
#include <inttypes.h>
#include <stdio.h>

int main(void) {
    // Format: { Word0 (LSB), Word1, Word2, Word3 (MSB) }

    // TEST 1: HELLO
    uint64_t input_1[4]  = { 0x00004F4C4C454804, 0x0, 0x0, 0x0 };
    uint64_t expect_1[4] = { 0x00004F4C4C4548,   0x0, 0x0, 0x0 };

    // TEST 2: RLE EXPANSION (0xAA x 10)
    uint64_t input_2[4]  = { 0x000000000000AAF7, 0x0, 0x0, 0x0 };
    uint64_t expect_2[4] = { 0xAAAAAAAAAAAAAAAA, 0xAAAA, 0x0, 0x0 };

    // TEST 3: FULL BUFFER FILL (0x77 x 32)
    uint64_t input_3[4]  = { 0x00000000000077E1, 0x0, 0x0, 0x0 };
    uint64_t expect_3[4] = { 0x7777777777777777, 0x7777777777777777, 
                            0x7777777777777777, 0x7777777777777777 };


    // Run Tests

    printf("Starting PackBits Decompression Tests...\n");
    printf("Source Address: %p\n", input_1);

    asm volatile("fence");

    // rs2/length = 512 bits
    ROCC_INSTRUCTION_SS(0, input_3, 512, 0x1); // Set source address

    asm volatile("fence");


    return 0;
}