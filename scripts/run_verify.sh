#!/bin/bash
cd ~/chipyard/tests/build
cmake ..
make conv_verify
cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/conv_verify.riscv