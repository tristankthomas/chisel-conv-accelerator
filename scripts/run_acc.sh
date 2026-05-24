#!/bin/bash
cd ~/chipyard/tests/build
cmake ..
make conv_acc
cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/conv_acc.riscv