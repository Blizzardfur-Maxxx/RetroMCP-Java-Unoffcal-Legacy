package org.mcphackers.mcp.tasks.info;

import org.mcphackers.mcp.tasks.Task;
import org.mcphackers.mcp.tasks.TaskReobfuscate;

public class TaskInfoReobfuscate extends TaskInfo {
	@Override
	public String title() {
		return "Reobfuscating";
	}

	@Override
	public String successMsg() {
		return "REOBFUSCATION SUCCESSFUL!";
	}

	@Override
	public String failMsg() {
		return "REOBFUSCATION FAILED!";
	}

	@Override
	public Task newTask(int side) {
		return new TaskReobfuscate(side, this);
	}

	@Override
	public boolean isMultiThreaded() {
		return true;
	}
}
