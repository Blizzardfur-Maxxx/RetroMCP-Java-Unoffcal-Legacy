package org.mcphackers.mcp.tasks;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.mcphackers.mcp.MCP;
import org.mcphackers.mcp.MCPPaths;
import org.mcphackers.mcp.ProgressListener;
import org.mcphackers.mcp.TaskParameter;
import org.mcphackers.mcp.tools.FileUtil;
import org.mcphackers.mcp.tools.Util;
import org.mcphackers.mcp.tools.mappings.MappingUtil;

import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class TaskReobfuscate extends Task {
	private final Map<String, String> recompHashes = new HashMap<>();
	private final Map<String, String> originalHashes = new HashMap<>();

	private MemoryMappingTree mappingTree = new MemoryMappingTree();

	private final Map<String, String> reobfPackages = new HashMap<>();

	public TaskReobfuscate(Side side, MCP instance) {
		super(side, instance);
	}

	public TaskReobfuscate(Side side, MCP instance, ProgressListener listener) {
		super(side, instance, listener);
	}

	@Override
	public void doTask() throws Exception {

		Path reobfJar = MCPPaths.get(mcp, chooseFromSide(MCPPaths.CLIENT_REOBF_JAR, MCPPaths.SERVER_REOBF_JAR));
		Path reobfBin = MCPPaths.get(mcp, chooseFromSide(MCPPaths.CLIENT_BIN, MCPPaths.SERVER_BIN));
		Path reobfDir = MCPPaths.get(mcp, chooseFromSide(MCPPaths.CLIENT_REOBF, MCPPaths.SERVER_REOBF));
		Path reobfMappings = MCPPaths.get(mcp, chooseFromSide(MCPPaths.CLIENT_MAPPINGS_RO, MCPPaths.SERVER_MAPPINGS_RO));
		Path deobfMappings = MCPPaths.get(mcp, chooseFromSide(MCPPaths.CLIENT_MAPPINGS_DO, MCPPaths.SERVER_MAPPINGS_DO));
		
		final boolean enableObfuscation = mcp.getOptions().getBooleanParameter(TaskParameter.OBFUSCATION);
		step();
		new TaskRecompile(side, mcp, this).doTask();
		step();
		new TaskUpdateMD5(side, mcp, this).updateMD5(true);

		if (Files.exists(reobfBin)) {
			boolean hasMappings = Files.exists(deobfMappings);
			FileUtil.deleteDirectoryIfExists(reobfDir);
			gatherMD5Hashes(true);
			gatherMD5Hashes(false);

			step();
			if (hasMappings) {
				MappingUtil.readMappings(deobfMappings, mappingTree);
				flipMappingTree();
				Map<String, Integer> obfIndexes = new HashMap<>();
				MappingUtil.modifyClasses(mappingTree, reobfBin, className -> {
					if (mappingTree.getClass(className) == null) { // Class isn't present in original mappings
						String packageName = className.lastIndexOf("/") >= 0 ? className.substring(0, className.lastIndexOf("/") + 1) : null;
						String obfPackage = reobfPackages.get(packageName);
						if (obfPackage == null) {
							return null;
						}
						String clsName = (className.lastIndexOf("/") >= 0 ? className.substring(className.lastIndexOf("/") + 1) : className);
						if(enableObfuscation) {
							int obfIndex = obfIndexes.getOrDefault(obfPackage, 0);
							String obfName = MappingUtil.getObfuscatedName(obfIndex);
							while(mappingTree.getClass(obfPackage + obfName, 0) != null) {
								obfIndex++;
								obfName = MappingUtil.getObfuscatedName(obfIndex);
							}
							if(obfIndex > obfIndexes.getOrDefault(obfPackage, 0)) {
								obfIndexes.put(obfPackage, obfIndex);
							}
							clsName = obfName;
						}
						return obfPackage + clsName;
					}
					return null; // Returning null skips remapping
				});
//				MappingUtil.modifyFields(mappingTree, reobfBin, (className, name, desc) -> {
//					if (mappingTree.getField(className, name, desc) == null) {
//						if(enableObfuscation) {
//							//TODO field and method reobf
//							int obfIndex = 0;
//							String obfName = MappingUtil.getObfuscatedName(obfIndex);
//							ClassMapping c = ((MappingTree)mappingTree).getClass(className);
//							String fName = c == null ? null : c.getDstName(0);
//							if(fName != null) {
//								//FIXME this won't do.
//								//Causes name clashing if there's something with a different descriptor but the same name.
//								while(mappingTree.getField(fName, obfName, desc, 0) != null) {
//									obfIndex++;
//									obfName = MappingUtil.getObfuscatedName(obfIndex);
//								}
//							}
//							return obfName;
//						}
//					}
//					return null;
//				});
				MappingUtil.writeMappings(reobfMappings, mappingTree);

			}

			Files.deleteIfExists(reobfJar);
			if (hasMappings) {
				MappingUtil.remap(reobfMappings, reobfBin, reobfJar, TaskDecompile.getLibraryPaths(mcp, side), "named", "official");
			} else {
				FileUtil.compress(reobfBin, reobfJar);
			}
			step();
			unpack(reobfJar, reobfDir);
		} else {
			throw new IOException(chooseFromSide("Client", "Server") + " classes not found!");
		}
	}

	private void flipMappingTree() throws IOException {
		((MappingTree) mappingTree).getClasses().forEach(classEntry -> {
			String obfName = classEntry.getName("official");
			String deobfName = classEntry.getName("named");
			if (deobfName != null) {
				String obfPackage = obfName.lastIndexOf("/") >= 0 ? obfName.substring(0, obfName.lastIndexOf("/") + 1) : "";
				String deobfPackage = deobfName.lastIndexOf("/") >= 0 ? deobfName.substring(0, deobfName.lastIndexOf("/") + 1) : "";
				if (!reobfPackages.containsKey(deobfPackage) && !deobfPackage.equals(obfPackage)) {
					reobfPackages.put(deobfPackage, obfPackage);
				}
			}
		});

		Map<String, String> namespaces = new HashMap<>();
		namespaces.put("named", "official");
		MemoryMappingTree namedTree = new MemoryMappingTree();
		MappingNsCompleter nsCompleter = new MappingNsCompleter(namedTree, namespaces);
		MappingSourceNsSwitch nsSwitch = new MappingSourceNsSwitch(nsCompleter, "named");
		mappingTree.accept(nsSwitch);
		mappingTree = namedTree;
	}
	
	public void setProgress(int progress) {
		switch (step) {
		case 1: {
			int percent = (int)((double)progress * 0.5D);
			super.setProgress(1 + percent);
			break;
		}
		default:
			super.setProgress(progress);
			break;
		}
	}

	protected void updateProgress() {
		switch (step) {
		case 1:
			setProgress("Recompiling...");
			break;
		case 2:
			setProgress("Gathering MD5 hashes...", 51);
			break;
		case 3:
			setProgress("Reobfuscating...", 52);
			break;
		case 4:
			setProgress("Unpacking...", 54);
			break;
		default:
			super.updateProgress();
			break;
		}
	}

	private void gatherMD5Hashes(boolean reobf) throws IOException {
		Path md5 = MCPPaths.get(mcp, reobf ? chooseFromSide(MCPPaths.CLIENT_MD5_RO, MCPPaths.SERVER_MD5_RO)
				: chooseFromSide(MCPPaths.CLIENT_MD5, MCPPaths.SERVER_MD5));

		try (BufferedReader reader = Files.newBufferedReader(md5)) {
			String line = reader.readLine();
			while (line != null) {
				String[] tokens = line.split(" ");
				if (reobf) {
					recompHashes.put(tokens[0], tokens[1]);
				} else {
					originalHashes.put(tokens[0], tokens[1]);
				}

				// Read next line
				line = reader.readLine();
			}
		}
	}

	private void unpack(final Path src, final Path destDir) throws IOException {
		Map<String, String> reobfClasses = new HashMap<>();
		((MappingTree) mappingTree).getClasses().forEach(classEntry -> reobfClasses.put(classEntry.getName("named"), classEntry.getDstName(0)));
		FileUtil.unzip(src, destDir, entry -> {
			String name = entry.getName().replace(".class", "");
			String deobfName = Util.getKey(reobfClasses, name);
			if (deobfName == null) deobfName = name.replace("\\", "/");
			String hash = originalHashes.get(deobfName);
			return !entry.isDirectory() && (hash == null || !hash.equals(recompHashes.get(deobfName)));
		});
	}
}
