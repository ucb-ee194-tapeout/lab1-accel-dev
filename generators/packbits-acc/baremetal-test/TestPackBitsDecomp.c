#include "../../../tests/rocc.h"
#include <inttypes.h>
#include <stdio.h>

int main(void) {
    // Format: { Word0 (LSB), Word1, Word2, Word3 (MSB) }

    // TEST 1: HELLO
    // uint64_t input_1[4]  = { 0x00004F4C4C454804, 0x0, 0x0, 0x0 };
    // uint64_t expect_1[4] = { 0x00004F4C4C4548,   0x0, 0x0, 0x0 };

    // TEST 2: RLE EXPANSION (0xAA x 10)
    uint64_t input_2[4]  = { 0x000000000000AAF7, 0x0, 0x0, 0x0 };
    // uint64_t expect_2[4] = { 0xAAAAAAAAAAAAAAAA, 0xAAAA, 0x0, 0x0 };
    
    // TEST 3: FULL BUFFER FILL (0x77 x 32)
    // __attribute__((aligned(32))) volatile uint64_t input_3[4] = {
    //     0x00000000000077E1ULL,  // bytes: E1 77 00 00 00 00 00 00
    //     0x0000000000000000ULL,
    //     0x0000000000000000ULL,
    //     0x0000000000000000ULL
    // };

    // uint64_t expect_3[4] = { 0x7777777777777777, 0x7777777777777777, 
    //                         0x7777777777777777, 0x7777777777777777 };

    // TEST 4: MIXED DATA
    // __attribute__((aligned(32))) volatile uint64_t input_4[4] = {
    //     0xAAFD2A008002AAFEULL,  // bytes: FE AA 02 80 00 2A FD AA
    //     0x00AAF7222A008003ULL,  // bytes: 03 80 00 2A 22 F7 AA 00
    //     0x0000000000000000ULL,
    //     0x0000000000000000ULL
    // };

    // uint64_t expect_4[4] = {
    //     0xAAAA2A0080AAAAAA,
    //     0xAAAA222A0080AAAA,
    //     0xAAAAAAAAAAAAAAAA,
    //     0x0000000000000000
    // };

    // TEST 5: INTEGRATION
    // __attribute__((aligned(32))) volatile uint64_t packbits_img_words[8] = {
    //     0x0100FDFF000100F9ULL,  // bytes 0–7
    //     0x0100FDFF000100FFULL,  // bytes 8–15
    //     0xFF00000700F900FFULL,  // bytes 16–23
    //     0x0100FE0000FF0000ULL,  // bytes 24–31
    //     0x00F900F900FEFFFFULL,  // bytes 32–39
    //     0x0000000000000000ULL,  // bytes 40–47
    //     0x0000000000000000ULL,  // bytes 48–55
    //     0x0000000000000000ULL   // bytes 56–63
    // };

    // allocate space for writeback - stack
    // size_t regionsize = sizeof(char) * (4 * 8); // 4 words of 8 bytes each

    // unsigned char * fixed_alloc_region = (unsigned char *)memalign(64, regionsize);

    volatile uint8_t outbuf[4096] __attribute__((aligned(64)));
    for (int i = 0; i < 4096; i++) {
        outbuf[i] = 0;
    }

    // Run Tests

    printf("Starting PackBits Decompression Tests...\n");
    printf("Source Address: %p\n", input_2);
    printf("Destination Address: %p\n", outbuf);

    asm volatile("fence");

    // rs2/length = 32 bytes
    ROCC_INSTRUCTION_SS(0, input_2, 32, 0x1); // Set source address
    ROCC_INSTRUCTION_S(0, outbuf, 0x2); // Set dest address
    ROCC_INSTRUCTION_S(0, (uintptr_t)(outbuf+32), 0x2); // Set dest address

    asm volatile("fence");

    // print outbuf
    printf("Decompressed Output:\n");
    for (int i = 0; i < 64; i++) {
        printf("%02X ", outbuf[i]);
        if ((i + 1) % 8 == 0) {
            printf("\n");
        }
    }


    return 0;
}