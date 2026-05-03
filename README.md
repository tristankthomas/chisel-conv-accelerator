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

6. `source ~/chipyard/env.sh`

## Structure

- `src/main/scala/` — Chisel accelerator source
- `src/test/scala/` — chiseltest unit tests
- `src/test/c/` — C benchmark and test code
- `scripts/` — simulation run scripts
- `results/` — benchmark output files

## Status

Work in progress.