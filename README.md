# chisel-conv-accelerator

Matrix convolution accelerator for CNN, implemented in Chisel and integrated into Chipyard as a RoCC accelerator attached to a Rocket core via the `custom0` opcode.

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
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new chipyard.config.AbstractConfig)
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
```

### Full simulation (Verilator)

```bash
cd ~/chipyard/tests/build
cmake ..
make <test_name>

cd ~/chipyard/sims/verilator
make CONFIG=ConvAcceleratorConfig run-binary BINARY=~/chipyard/tests/build/<test_name>.riscv
```

## Custom Instructions

The accelerator uses the `custom0` opcode with three R-format instructions:

| Instruction | funct7 | xd | xs1 | xs2 | rs1 | rs2 | rd |
|---|---|---|---|---|---|---|---|
| SET_INPUT | 0 | 0 | 1 | 1 | &input | &output | - |
| START | 1 | 0 | 1 | 1 | &kernel | kernel_size | - |
| POLL_STATUS | 2 | 1 | 0 | 0 | - | - | status |

Status word: `bit 0 = done`, `bit 1 = error`

Supported kernel sizes: 1x1, 3x3, 5x5 (odd sizes only).

## Architecture

### Convolution Algorithm

2D convolution with implicit zero padding. For each output element `(i,j)`, a KxK window is extracted from the input matrix centred on `(i,j)` — out-of-bounds accesses return zero. The window is dot-producted with the kernel and the result is right-shifted by 8 to correct for 8.8 fixed-point multiplication.

### Hardware

```
ConvAccelerator (LazyRoCC, custom0)
  └── ConvAcceleratorModuleImp
        ├── Instruction decode (SET_INPUT, START, POLL_STATUS)
        ├── FSM (IDLE → LOAD_INPUT → LOAD_KERNEL → COMPUTE → STORE → WAIT_LAST_STORE → DONE)
        ├── io.mem interface (HellaCacheIO, sequential load/store)
        └── Convolution (25 parallel MACs + adder tree, combinational)
```

**Convolution module:** 25 parallel 16-bit multipliers with a 5-level adder tree. One output element computed per cycle. Result is 40-bit to prevent overflow during accumulation.

**Memory interface:** Sequential load/store via `io.mem` (L1 D-cache). One request in flight at a time using a `reqPending` register. Store completion tracked via response counter to guarantee memory consistency before signalling done.

**Internal buffers:**
- `inputBuffer`: 32×32×16-bit register array
- `kernelBuffer`: 25×16-bit register array (flattened kernel)
- `outputBuffer`: 32×32×32-bit register array

**Data format:** 8.8 fixed-point. Output is 32-bit (24.8 effective range after >>8 shift).

### FSM States

| State | Description |
|---|---|
| IDLE | Waiting for START instruction |
| LOAD_INPUT | Sequential load of 1024 input elements via io.mem |
| LOAD_KERNEL | Sequential load of K² kernel elements via io.mem |
| COMPUTE | 1024 cycles, one output element per cycle |
| STORE | Sequential store of 1024 output elements via io.mem |
| WAIT_LAST_STORE | Wait for all 1024 store acknowledgements |
| DONE | Set done flag, return to IDLE |

## Structure

```
src/main/scala/
  ConvAccelerator.scala   -- RoCC module, FSM, memory interface
  Convolution.scala       -- Pure compute module (25 parallel MACs)
  MAC.scala               -- Prototype MAC module (separate task)
  Config.scala            -- WithConvAccelerator mixin

src/test/scala/
  ConvolutionTest.scala   -- ChiselTest unit tests for Convolution module

src/test/c/
  conv_sw.c               -- Software baseline convolution
  conv_acc.c              -- Accelerator test with RoCC macros
```

## Status

- [x] Convolution module (verified with ChiselTest)
- [x] RoCC interface and instruction decode
- [x] FSM with memory load/store
- [ ] C test code and performance benchmarking
- [ ] Verilator simulation
- [ ] Performance optimisations (pipelined memory, line buffer)