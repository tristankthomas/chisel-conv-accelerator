#!/bin/bash
TEST=${1:-conv_acc}

cd ~/chipyard/tests/build
cmake ..
make $TEST

cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/$TEST.riscv