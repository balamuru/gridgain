/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*
 * ___    _________________________ ________
 * __ |  / /____  _/__  ___/__  __ \___  __ \
 * __ | / /  __  /  _____ \ _  / / /__  /_/ /
 * __ |/ /  __/ /   ____/ / / /_/ / _  _, _/
 * _____/   /___/   /____/  \____/  /_/ |_|
 *
 */

package org.gridgain.visor.commands.vvm

import org.gridgain.visor._
import org.gridgain.visor.commands.VisorConsoleCommand
import visor._
import org.jetbrains.annotations.Nullable
import collection.immutable._
import org.gridgain.grid._
import kernal.GridNodeAttributes._
import org.gridgain.grid.util.typedef.X
import util.{GridUtils => U}
import java.net._
import java.io.File
import org.gridgain.scalar._
import scalar._
import scala.util.control.Breaks._
import collection.JavaConversions._

/**
 * ==Overview==
 * Contains Visor command `vvm` implementation.
 *
 * ==Help==
 * {{{
 * +-----------------------+
 * | vvm | Opens VisualVM. |
 * +-----------------------+
 * }}}
 *
 * ====Specification====
 * {{{
 *     vvm "{-home=dir} {-id8=<node-id8>} {-id=<node-id>}"
 * }}}
 *
 * ====Arguments====
 * {{{
 *     -home=dir
 *         VisualVM home directory.
 *         If not specified, PATH and JAVA_HOME will be searched.
 *     -id8=<node-id8>
 *         ID8 of node.
 *         Either '-id8' or '-id' can be specified.
 *     -id=<node-id>
 *         Full ID of node.
 *         Either '-id8' or '-id' can be specified.
 * }}}
 *
 * ====Examples====
 * {{{
 *     vvm "-id8=12345678"
 *         Opens VisualVM connected to JVM for node with '12345678' ID8.
 *     vvm "-id=5B923966-85ED-4C90-A14C-96068470E94D"
 *         Opens VisualVM connected to JVM for node with given full node ID.
 *     vvm "-home=C:\VisualVM -id8=12345678"
 *         Opens VisualVM installed in 'C:\VisualVM' directory for specified node.
 *     vvm
 *         Opens VisualVM connected to all nodes.
 * }}}
 */
class VisorVvmCommand {
    /**
     * Prints error message and advise.
     *
     * @param errMsgs Error messages.
     */
    private def scold(errMsgs: Any*) {
        assert(errMsgs != null)

        warn(errMsgs: _*)
        warn("Type 'help vvm' to see how to use this command.")
    }

    /**
     * ===Command===
     * Opens VisualVM connected to JVM indicated by the node id.
     *
     * ===Examples===
     * <ex>vvm "-id8=12345678"</ex>
     * Opens VisualVM connected to JVM for node with '12345678' ID8.
     *
     * <ex>vvm "-id=5B923966-85ED-4C90-A14C-96068470E94D"</ex>
     * Opens VisualVM connected to JVM for node with given full node ID.
     *
     * <ex>vvm "-home=C:\VisualVM -id8=12345678"</ex>
     * Opens VisualVM installed in 'C:\Visual\VM' directory for specified node.
     *
     * @param args Command parameters.
     */
    def vvm(@Nullable args: String) = breakable {
        if (!isConnected)
            adviseToConnect()
        else {
            val argLst = parseArgs(args)

            val vvmHome = argValue("home", argLst) getOrElse X.getSystemOrEnv("VVM_HOME")
            val id8 = argValue("id8", argLst) getOrElse null
            val id = argValue("id", argLst) getOrElse null

            var vvmCmd: String = null

            val ext = if (U.isWindows) ".exe" else ""

            val fs = File.separator

            if (vvmHome != null && !vvmHome.isEmpty) {
                vvmCmd = vvmHome + fs + "bin" + fs + "visualvm" + ext

                if (!new File(vvmCmd).exists)
                    vvmCmd = vvmHome + fs + "bin" + fs + "jvisualvm" + ext
            }

            if (vvmCmd == null || vvmCmd.isEmpty) {
                breakable {
                    for (p <- System.getenv("PATH").split(System.getProperty("path.separator"))) {
                        val f1 = p + fs + "visualvm" + ext
                        val f2 = p + fs + "jvisualvm" + ext

                        if (new File(f1).exists) {
                            vvmCmd = f1

                            break()
                        }
                        else if (new File(f2).exists) {
                            vvmCmd = f2

                            break()
                        }
                    }
                }
            }

            if (vvmCmd == null || vvmCmd.isEmpty)
                vvmCmd = X.getSystemOrEnv("JAVA_HOME") + fs + "bin" + fs + "jvisualvm" + ext

            if (!new File(vvmCmd).exists)
                warn(
                    "Can't find Visual VM",
                    "Specify '-home' parameter or VVM_HOME environment property to provide " +
                        "Visual VM installation folder."
                ).^^

            var nodes: scala.collection.Seq[GridNode] = null

            if (id8 != null && id != null)
                scold("Only one of '-id8' or '-id' is allowed.").^^
            else if (id8 == null && id == null)
                nodes = grid.forRemotes().nodes().toSeq
            else
                if (id8 != null) {
                    val ns = nodeById8(id8)

                    if (ns.isEmpty)
                        scold("Unknown 'id8' value: " + id8).^^
                    else if (ns.size != 1)
                        scold("'id8' resolves to more than one node (use full 'id' instead): " + id8).^^
                    else
                        nodes = Seq(ns.head)
                }
                else if (id != null)
                    try {
                        val node = grid.node(java.util.UUID.fromString(id))

                        if (node == null)
                            scold("'id' does not match any node: " + id).^^

                        nodes = Seq(node)
                    }
                    catch {
                        case e: IllegalArgumentException => scold("Invalid node 'id': " + id).^^
                    }

            var started = false

            val neighbors = grid.forHost(grid.localNode).nodes()

            for (node <- nodes if !neighbors.contains(node)) {
                var addr: String = null

                breakable {
                    for (a <- node.addresses if U.reachable(InetAddress.getByName(a), 2000)) {
                        addr = a

                        break()
                    }
                }

                if (addr == null)
                    scold("Visor failed to get reachable address for node (skipping): " + nid8(node))
                else {
                    val port = node.attribute[java.lang.Integer](ATTR_JMX_PORT)

                    if (port == null)
                        warn("JMX is not enabled for node (skipping): " + nid8(node))
                    else {
                        // Sequential calls to VisualVM will not start separate processes
                        // but will add new JMX connection to it.
                        Runtime.getRuntime.exec(vvmCommandArray(vvmCmd + " --openjmx " + addr + ":" + port))

                        started = true
                    }
                }
            }

            if (!started)
                Runtime.getRuntime.exec(vvmCommandArray(vvmCmd))
        }
    }

    /**
     * Returns VisualVM command array specific for a particular platform.
     *
     * @param vvmCmd VisualVM command.
     */
    private def vvmCommandArray(vvmCmd: String): Array[String] = {
        if (U.isWindows) Array("cmd", "/c", vvmCmd) else Array(vvmCmd)
    }

    /**
     * ===Command===
     * Opens VisualVM connected to all nodes.
     *
     * ==Examples==
     * <ex>vvm</ex>
     * Opens VisualVM with all nodes.
     */
    def vvm() {
        vvm(null)
    }
}

/**
 * Companion object that does initialization of the command.
 */
object VisorVvmCommand {
    // Adds command's help to visor.
    addHelp(
        name = "vvm",
        shortInfo = "Opens VisualVM for nodes in topology.",
        spec = List("vvm {-home=dir} {-id8=<node-id8>} {-id=<node-id>}"),
        args = List(
            "-home=dir" -> List(
                "VisualVM home folder.",
                "If not specified, PATH and JAVA_HOME will be searched"
            ),
            "-id8=<node-id8>" -> List(
                "ID8 of node.",
                "Note that either '-id8' or '-id' can be specified and " +
                    "you can also use '@n0' ... '@nn' variables as shortcut to <node-id8>."
            ),
            "-id=<node-id>" -> List(
                "Full ID of node.",
                "Either '-id8' or '-id' can be specified."
            )
        ),
        examples = List(
            "vvm -id8=12345678" ->
                "Opens VisualVM connected to JVM for node with '12345678' ID8.",
            "vvm -id8=@n0" ->
                "Opens VisualVM connected to JVM for node with given node ID8 taken from 'n0' memory variable.",
            "vvm -id=5B923966-85ED-4C90-A14C-96068470E94D" ->
                "Opens VisualVM connected to JVM for node with given full node ID.",
            "vvm -home=C:\\VisualVM -id8=12345678" ->
                "Opens VisualVM installed in 'C:\\VisualVM' folder for specified node.",
            "vvm" ->
                "Opens VisualVM connected to all nodes."
        ),
        ref = VisorConsoleCommand(cmd.vvm, cmd.vvm)
    )

    /** Singleton command. */
    private val cmd = new VisorVvmCommand

    /**
     * Singleton.
     */
    def apply() = cmd

    /**
     * Implicit converter from visor to commands "pimp".
     *
     * @param vs Visor tagging trait.
     */
    implicit def fromVvm2Visor(vs: VisorTag) = cmd
}
