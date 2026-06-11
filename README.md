# chisel-conv-accelerator

Matrix convolution accelerator for CNN inference, implemented in Chisel and integrated into Chipyard as a RoCC accelerator attached to a Rocket core via the `custom0` opcode. Supports 8.8 fixed-point and IEEE 754 single-precision floating-point convolution with kernel sizes 1×1, 3×3, and 5×5.

## Dependencies

- Chipyard commit: `48f904aefbb3903dce6efa7901982642853ae6a7`
- Java 8, sbt, Conda (via Chipyard setup)

## Setup

1. Follow [Chipyard installation instructions](https://chipyard.readthedocs.io)
2. Clone this repo into `chipyard/generators/conv-accelerator`
3. Add the following to `chipyard/build.sbt` after the gemmini lazy val (~line 367):

```scala
lazy val convAccelerator = (project in file("generators/conv-accelerator"))
  .dependsOn(rocketchip)
  .settings(libraryDependencies ++= rocketLibDeps.value)
  .settings(commonSettings)
```

4. Add to the `optionalModules` list in the chipyard lazy val:

```scala
"conv-accelerator" -> convAccelerator,
```

5. Add to `chipyard/generators/chipyard/src/main/scala/config/RoCCAcceleratorConfigs.scala`:

```scala
class ConvAcceleratorConfig extends Config(
  new convaccelerator.WithConvAccelerator ++
  new RocketConfig)
```

6. Add test executables to `chipyard/tests/CMakeLists.txt`.

In the Build section:
```cmake
set(CONV_ACC_TESTS ${CMAKE_SOURCE_DIR}/../generators/conv-accelerator/src/test/c)
include_directories(${CMAKE_SOURCE_DIR})
include_directories(${CMAKE_SOURCE_DIR}/../toolchains/riscv-tools/riscv-tests/env)

add_executable(<test_name> ${CONV_ACC_TESTS}/<test_name>.c)
```

In the Disassembly section:
```cmake
add_dump_target(<test_name>)
```

7. `source ~/chipyard/env.sh`

## Running Tests

### Unit tests (ChiselTest)

```bash
cd ~/chipyard
sbt "convAccelerator/testOnly convaccelerator.ConvolutionTest"
sbt "convAccelerator/testOnly convaccelerator.ConvolutionFPTest"
```

### Full simulation (Verilator)

**Option 1: Manual build and execution**
```bash
cd ~/chipyard/tests/build
cmake ..
make <test_name>

cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/<test_name>.riscv
```

**Option 2: Using the automated script**
```bash
./scripts/run_test.sh <test_name>
```

## Custom Instructions

The accelerator uses the `custom0` opcode with four R-format instructions:

| Instruction | funct7 | xd | xs1 | xs2 | rs1     | rs2         | rd     |
|---|---|---|---|---|---|---|---|
| SET_INPUT   | 0      | 0  | 1   | 1   | &input  | &output     | -      |
| START_INT   | 1      | 0  | 1   | 1   | &kernel | kernel_size | -      |
| START_FP    | 2      | 0  | 1   | 1   | &kernel | kernel_size | -      |
| POLL_STATUS | 3      | 1  | 0   | 0   | -       | -           | status |

Status word: `bit 0 = done`, `bit 1 = error`

Supported kernel sizes: 1×1, 3×3, 5×5 (odd sizes only). K=7 and above assert the error flag.

## Architecture

### Convolution Algorithm

2D convolution with implicit zero padding. For each output element `(i,j)`, a K×K window is extracted from the input matrix centred on `(i,j)` — out-of-bounds accesses return zero. Fixed-point results are right-shifted by 8 to correct for 8.8 fixed-point multiplication. Floating-point results use Berkeley HardFloat MulRecFN and AddRecFN modules.

### Hardware

```
ConvAccelerator (LazyRoCC, custom0)
  └── ConvAcceleratorModuleImp
        ├── Instruction decode (SET_INPUT, START_INT, START_FP, POLL_STATUS)
        ├── FSM (IDLE → LOAD_KERNEL → STREAM → DONE)
        ├── Line buffer (6-slot circular ring buffer, 32 elements per row)
        ├── Row prefetcher (up to 16 in-flight load requests)
        ├── Bus arbiter (store priority, tag-based load/store response routing)
        ├── Convolution × PARALLEL      -- fixed-point engines (16-bit input, 40-bit accumulator)
        └── ConvolutionFP × FP_PARALLEL -- floating-point engines (Berkeley HardFloat)
```

### Key Design Parameters

| Parameter    | Value | Description                       |
|---|---|---|
| PARALLEL     | 4     | Fixed-point compute units         |
| FP_PARALLEL  | 2     | Floating-point compute units      |
| MAX_INFLIGHT | 16    | Maximum in-flight memory requests |
| Line buffer  | 6     | Circular buffer slots (rows)      |
| Input width  | 32    | Fixed matrix dimension            |

### Performance (Verilator, SimDRAM zero-latency backend, K=3)

| Version        | Key change                            | Cycles  | Speedup vs SW |
|---|---|---|---|
| SW baseline    | --                                    | 237,458 | 1×            |
| v1 naive       | Sequential load/compute/store         | 7,329   | 32×           |
| v2 pipelined   | 16 in-flight requests                 | 3,171   | 75×           |
| v2.1 64-bit    | 4 elements per load and store request | 1,622   | 146×          |
| v3 streaming   | Output streaming                      | 1,373   | 173×          |
| v4 line buffer | 6-slot ring buffer and row prefetcher | 1,137   | 209×          |
| v5 spatial x4  | 4 parallel compute units              | 607     | 386×          |

Peak speedup: **820x for K=5 fixed-point** (607 cycles vs 530,622 cycles SW).

### Memory Subsystem

The streaming phase uses a 6-slot circular line buffer. Row `n` always occupies slot `n mod 6`. A row prefetcher issues load requests up to `6 - floor(K/2)` rows ahead of the compute engine. The throttle condition:

```
rowsRequested < outRow + (6 - floor(K/2))
```

is applied to requests issued (not responses received) to account for in-flight requests. Loads and stores share the single 64-bit HellaCacheIO port with store priority arbitration. Load and store responses are distinguished via tag bit 4.

### Compute Engine

**Fixed-point:** 8.8 format. Each engine instantiates 25 parallel 16-bit multipliers feeding a 5-level adder tree with a 40-bit accumulator to prevent overflow. One output element per cycle per engine. Results truncated to 16 bits after right-shift by 8.

**Floating-point:** IEEE 754 single-precision via Berkeley HardFloat. Each engine uses 25 MulRecFN multipliers and an AddRecFN adder tree. Results compared within 4 ULP tolerance in test code.

### Resource Utilisation (Xilinx Artix-7 200T, post-synthesis)

| Resource   | Accelerator only | % of device |
|---|---|---|
| Slice LUTs | 87,262           | 65%         |
| Registers  | 7,824            | 3%          |
| DSP48      | 250              | 34%         |
| Block RAM  | 0                | 0%          |

DSP breakdown: 100 for fixed-point (4 engines x 25 multipliers x 1 DSP each), 150 for floating-point (2 engines x 25 MulRecFN x 3 DSPs each). Line buffer implemented entirely in flip-flop registers.

### FSM States

| State       | Description                                            |
|---|---|
| IDLE        | Waiting for START instruction                          |
| LOAD_KERNEL | Pipelined load of K^2 kernel elements via HellaCacheIO |
| STREAM      | Concurrent input prefetch, compute, and output store   |
| DONE        | Set done flag, return to IDLE                          |

## Structure

```
src/main/scala/
  ConvAccelerator.scala    -- RoCC module, FSM, memory interface, bus arbiter
  Convolution.scala        -- Fixed-point compute module (PARALLEL instances)
  ConvolutionFP.scala      -- Floating-point compute module (FP_PARALLEL instances)
  Configs.scala            -- WithConvAccelerator mixin
  MAC.scala                -- Prototype MAC module

src/test/scala/
  ConvolutionTest.scala    -- ChiselTest unit tests for fixed-point Convolution module
  ConvolutionFPTest.scala  -- ChiselTest unit tests for floating-point ConvolutionFP module

src/test/c/
  conv_verify.c            -- Fixed-point correctness and performance test (LCG random)
  conv_fp_verify.c         -- Floating-point correctness and performance test
  conv_acc.c               -- Legacy fixed-point accelerator test
  conv_fp_acc.c            -- Legacy floating-point accelerator test
  conv_sw.c                -- Software baseline fixed-point convolution
  conv_fp_sw.c             -- Software baseline floating-point convolution

results/
  v1.0-naive_*             -- Naive sequential implementation results
  v2.0-pipelined-mem_*     -- Pipelined memory results
  v2.1-64bit-loads_*       -- 64-bit packed request results
  v3.1-output-streaming_*  -- Output streaming results
  v4.1-16bit-output_*      -- Line buffer results
  v5.0-parallel-*          -- Spatial parallelism results (2x and 4x, int and fp)

scripts/
  make_plots.py            -- Generates all report and presentation figures
  run_test.sh              -- Automatically builds and runs C test
```

