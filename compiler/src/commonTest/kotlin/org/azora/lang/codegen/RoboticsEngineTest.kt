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
                    fin rotation = std::quaternion(0.0, 0.0, 0.0, 2.0)
                    fin moved = std::pose(std::vector3(1.0, 2.0, 3.0), rotation)
                    fin point = std::transformPoint(moved, std::vector3(1.0, 0.0, 0.0))
                    std::println(point.x)
                    std::println(point.y)
                    std::println(point.z)

                    fin limit = std::JointLimit("arm", -1.0, 1.0, 2.0, 4.0)
                    std::println(std::withinLimit(0.5, limit))
                    fin command = std::boundedPositionCommand("arm", 5.0, 100L, limit)
                    std::println(command.value)

                    fin names: std::Array<std::String> = @std::arr["arm"]
                    fin positions: std::Array<std::Double> = @std::arr[0.0]
                    fin velocities: std::Array<std::Double> = @std::arr[0.0]
                    fin point0 = std::TrajectoryPoint(0.0, positions, velocities)
      fin points: std::Array<std::TrajectoryPoint> = @std::arr[take point0]
                    fin trajectory = std::Trajectory(names, points)
                    std::println(std::validTrajectory(trajectory))

      fin badNames: std::Array<std::String> = @std::arr["arm"]
      fin badPositions: std::Array<std::Double> = @std::arr[0.0]
      fin badVelocities: std::Array<std::Double> = @std::arr[0.0]
      fin badPoint = std::TrajectoryPoint(-1.0, badPositions, badVelocities)
      fin badPoints: std::Array<std::TrajectoryPoint> = @std::arr[take badPoint]
      fin badTrajectory = std::Trajectory(badNames, badPoints)
                    std::println(std::validTrajectory(badTrajectory))
                }
                """,
            ),
        )
    }
}
