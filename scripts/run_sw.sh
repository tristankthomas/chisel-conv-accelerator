#!/bin/bash
cd ~/chipyard/tests/build
make conv_sw
cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/conv_sw.riscv