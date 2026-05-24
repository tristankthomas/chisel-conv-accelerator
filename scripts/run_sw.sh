#!/bin/bash
cd ~/chipyard/tests/build
cmake ..
make conv_sw
cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/conv_sw.riscv