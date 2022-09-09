package org.mcphackers.mcp.tasks;

import org.mcphackers.mcp.ProgressInfo;
import org.mcphackers.mcp.tasks.info.TaskInfo;

public abstract class Task {
	
	protected static final int CLIENT = 0;
	protected static final int SERVER = 1;
	
	protected int step = 0;
	protected final int side;
	protected final TaskInfo info;
	
	protected Task(int side, TaskInfo info) {
		this.side = side;
		this.info = info;
	}

	public abstract void doTask() throws Exception;

	public ProgressInfo getProgress() {
		return new ProgressInfo("Idle", (step > 0 ? 1 : 0), 1);
	}
	
	protected void step() {
		step++;
	}
	
	protected String chooseFromSide(String... strings) {
		if(side < strings.length) {
			return strings[side];
		}
		return null;
	}
}
