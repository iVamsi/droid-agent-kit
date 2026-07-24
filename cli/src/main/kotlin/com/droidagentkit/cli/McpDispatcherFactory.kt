package com.droidagentkit.cli

import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.mcp.DroidAgentMcpDispatcher
import java.nio.file.Path

internal fun mcpDispatcher(config: DroidAgentConfig, root: Path): DroidAgentMcpDispatcher =
    DroidAgentMcpDispatcher(config, root, exposedGroups = config.resolvedExposedToolGroups())
