# chisel-conv-accelerator

Matrix convolution accelerator for CNN, implemented in Chisel and integrated into Chipyard as a RoCC accelerator.

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

5. Add to `chipyard/generators/chipyard/src/main/scala/config/TutorialConfigs.scala`:

```scala
import convaccelerator._

class MACConfig extends Config(
  new WithMAC ++
  new RocketConfig)
```

6. Add the following to `chipyard/tests/CMakeLists.txt` in the Build section:

```cmake
set(CONV_ACC_TESTS /home/trist/chipyard/generators/conv-accelerator/src/test/c)
include_directories(${CMAKE_SOURCE_DIR})
include_directories(${CMAKE_SOURCE_DIR}/../toolchains/riscv-tools/riscv-tests/env)

add_executable(matmul ${CONV_ACC_TESTS}/matmul.c)
add_executable(mac_test ${CONV_ACC_TESTS}/MAC_test.c)
```

And in the Disassembly section:

```cmake
add_dump_target(matmul)
add_dump_target(mac_test)
```

7. `source ~/chipyard/env.sh`

## Running Tests

```bash
cd ~/chipyard/tests/build
cmake ..
make matmul
make mac_test
```

```bash
cd ~/chipyard/sims/verilator
make CONFIG=RocketConfig run-binary BINARY=~/chipyard/tests/build/matmul.riscv
make CONFIG=MACConfig run-binary BINARY=~/chipyard/tests/build/mac_test.riscv
```

## Structure

- `src/main/scala/` — Chisel accelerator source
- `src/test/scala/` — chiseltest unit tests
- `src/test/c/` — C benchmark and test code
- `scripts/` — simulation run scripts
- `results/` — benchmark output files

## Status

Work in progress.