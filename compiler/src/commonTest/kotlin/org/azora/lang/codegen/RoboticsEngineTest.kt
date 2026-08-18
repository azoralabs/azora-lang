package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Verifies engine robotics geometry and motion contracts without hardware access. */
class RoboticsEngineTest {

    private val roboticsSource = File("../azora-engine/robotics.az").readText()

    private fun run(source: String): String {
        val result = Compiler(
            librarySources = listOf(LibrarySource("robotics.az", roboticsSource)),
        ).compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun geometryLimitsAndTrajectoriesRemainInspectable() {
        assertEquals(
    "0.0\n2.0\n3.0\ntrue\n1.0\ntrue\nfalse",
            run(
                """
                import azora.engine.robotics
                import std.container.array
                import std.io

                func main() {
                    fin rotation = quaternion(0.0, 0.0, 0.0, 2.0)
                    fin moved = pose(vector3(1.0, 2.0, 3.0), rotation)
                    fin point = transformPoint(moved, vector3(1.0, 0.0, 0.0))
                    println(point.x)
                    println(point.y)
                    println(point.z)

                    fin limit = JointLimit("arm", -1.0, 1.0, 2.0, 4.0)
                    println(withinLimit(0.5, limit))
                    fin command = boundedPositionCommand("arm", 5.0, 100L, limit)
                    println(command.value)

                    fin names: Array<String> = @arr["arm"]
                    fin positions: Array<Double> = @arr[0.0]
                    fin velocities: Array<Double> = @arr[0.0]
                    fin point0 = TrajectoryPoint(0.0, positions, velocities)
      fin points: Array<TrajectoryPoint> = @arr[take point0]
                    fin trajectory = Trajectory(names, points)
                    println(validTrajectory(trajectory))

      fin badNames: Array<String> = @arr["arm"]
      fin badPositions: Array<Double> = @arr[0.0]
      fin badVelocities: Array<Double> = @arr[0.0]
      fin badPoint = TrajectoryPoint(-1.0, badPositions, badVelocities)
      fin badPoints: Array<TrajectoryPoint> = @arr[take badPoint]
      fin badTrajectory = Trajectory(badNames, badPoints)
                    println(validTrajectory(badTrajectory))
                }
                """,
            ),
        )
    }
}
