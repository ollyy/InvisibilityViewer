package com.cyprias.invisibilityviewer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;

import net.minecraft.server.v1_4_R1.MobEffectList;
import net.minecraft.server.v1_4_R1.WatchableObject;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_4_R1.entity.CraftLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.xml.sax.SAXException;

import com.comphenix.protocol.Packets;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;

public class InvisibilityViewer extends JavaPlugin {
	public static String chatPrefix = "�f[�aIV�f] ";
	static InvisibilityViewer instance;
	
	private ProtocolManager protocolManager;
	String pluginName;

	public Events events;

	public VersionChecker versionChecker;
	public Commands commands;

	MobEffectList invisEffect;
	
	final Byte defaultMask = 0x00;
	final Byte invisibleMask = 0x20;
	
	public void onLoad() {
		pluginName = getDescription().getName();
		new Config(this);
		this.events = new Events(this);
		this.commands = new Commands(this);


		try {
			Metrics metrics = new Metrics(this);
			metrics.start();
		} catch (IOException e) {
		}

		invisEffect = MobEffectList.INVISIBILITY;
		Logger.info(String.format("%s v%s is loaded.", pluginName, this.getDescription().getVersion()));
	}
	
	
	public void onEnable() {
		instance = this;
		Config.reloadOurConfig(this);

		if (Config.checkNewVersionOnStartup == true)
			checkVersion();
		
		getServer().getPluginManager().registerEvents(this.events, this);
		getCommand("iv").setExecutor(this.commands);



		addPacketListener();
		fillViewInvis();

		Logger.info(String.format("%s v%s is enabled.", pluginName, this.getDescription().getVersion()));
	}

	public static final JavaPlugin getInstance() {
		return instance;
	}

	
	public void onDisable() {
		protocolManager = ProtocolLibrary.getProtocolManager();
		protocolManager.removePacketListeners(this);
		viewInvis.clear();
		
		for (Entry<Entity, Integer> e  : distanceTaskIDs.entrySet()) {
			getServer().getScheduler().cancelTask(e.getValue());
		}
		getServer().getScheduler().cancelTasks(this);
		
		Logger.info(String.format("%s v%s is disabled.", pluginName, this.getDescription().getVersion()));
	}

	private void checkVersion() {
		getServer().getScheduler().runTaskAsynchronously(instance, new Runnable() {
			public void run() {
				try {
					VersionChecker version = new VersionChecker("http://dev.bukkit.org/server-mods/invisibilityviewer/files.rss");
					VersionChecker.versionInfo info = (version.versions.size() > 0) ? version.versions.get(0) : null;
					if (info != null) {
						String curVersion = getDescription().getVersion();
						if (VersionChecker.compareVersions(curVersion, info.getTitle()) < 0) {
							Logger.warning("We're running v" + curVersion + ", v" + info.getTitle() + " is available");
							Logger.warning(info.getLink());
						}
					}
				} catch (SAXException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (ParserConfigurationException e) {
					e.printStackTrace();
				}

			}
		});
	}
	
	public static HashMap<String, Integer> viewInvis = new HashMap<String, Integer>();

	private Entity getEntity(List<Entity> ents, int eID) {
		for (int i = 0; i < ents.size(); i++) {
			if (ents.get(i).getEntityId() == eID)
				return ents.get(i);
		}

		return null;
	}

	private Boolean canView(Player player, Entity entity) {
		if (entity instanceof Player) {
			if (player.hasPermission("invisibilityviewer.canView.player") && hasMask(viewInvis.get(player.getName()), maskPlayer)) {
				return true;
			}

		} else {
			if (player.hasPermission( "invisibilityviewer.canView.other") && hasMask(viewInvis.get(player.getName()), maskOther)) {
				return true;
			}
		}
		return false;
	}

	private Boolean distanceView(Player player, Entity entity) {
		double dist = player.getLocation().distance(entity.getLocation());
		if (dist <= Config.distanceRadius) {
			if (entity instanceof Player) {
				if (player.hasPermission("invisibilityviewer.distanceView.player")) {
					return true;
				}

			} else {
				if (player.hasPermission( "invisibilityviewer.distanceView.other")) {
					return true;
				}
			}
		}
		return false;
	}

	private PacketAdapter pAdapter;

	private void removePacketListener() {
		protocolManager.removePacketListener(pAdapter);
	}

	
	
	private void addPacketListener() {
		protocolManager = ProtocolLibrary.getProtocolManager();
		pAdapter = new PacketAdapter(this, ConnectionSide.SERVER_SIDE, ListenerPriority.NORMAL, Packets.Server.ENTITY_METADATA) {
			public void onPacketSending(PacketEvent event) {
				PacketContainer packet = event.getPacket();

				switch (event.getPacketID()) {
				case Packets.Server.ENTITY_METADATA: // Entity Metadata

					StructureModifier<Object> mods = packet.getModifier();

					Entity entity = null;
					if (mods.size() > 0) {
						int eID = (Integer) mods.read(0);
					
						Player player = event.getPlayer();
						if (player.getEntityId() == eID)
							return;
						
						entity = getEntity(player.getWorld().getEntities(), eID);

						for (int i = 1; i < mods.size(); i++) {//0=entID
								if (mods.read(i) instanceof ArrayList) {
									@SuppressWarnings("unchecked")
									ArrayList<WatchableObject> list = (ArrayList<WatchableObject>) mods.read(i);

									Byte entFlag;
									for (int a = 0; a < list.size(); a++) {

										if (list.get(a) instanceof WatchableObject) {

											entFlag = getPacketFlag((WatchableObject) list.get(a));
											if (entFlag == null)
												continue;
											
											if (Config.debugMessages == true) 
												Logger.info(player.getName() + "'s receving flag " + entFlag + " on " + ((entity instanceof Player)  ? ((Player) entity).getName() : entity.getType() + "("+eID+")"));
											

											
											if (Config.debugNoIntercept == true)
												continue;
											
											
											if (entFlag == invisibleMask) {

												if (Config.distanceEnabled == true && !distanceTaskIDs.containsKey(entity)) {
													//Start a timer to check distance.
													invisDistanceTask task = new invisDistanceTask(entity);
													int taskID = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, task, 0L, (Config.distanceFrequency * 20L));
													task.setId(taskID);
													distanceTaskIDs.put(entity, taskID);
												}
												
												if ((Config.distanceEnabled == true && distanceView(player, entity)) || canView(player, entity) == true) {
													
													//Replace the hide byte with the view byte.
													mods.write(i, list.set(a, new WatchableObject(list.get(a).c(), list.get(a).a(), defaultMask)));
													
													if (Config.debugMessages == true) 
														Logger.info("Switched " + player.getName() + "'s receving flag " + entFlag + " on " + ((entity instanceof Player)  ? ((Player) entity).getName() : entity.getType() + "("+eID+")") + " to " + defaultMask);
												}

											} else if (entFlag == defaultMask && distanceTaskIDs.containsKey(entity)) {
												plugin.getServer().getScheduler().cancelTask(distanceTaskIDs.get(entity));
												distanceTaskIDs.remove(entity);
											}
										}

									}

								}
						}
					}

					break; //case
				}
			}
		};

		protocolManager.addPacketListener(pAdapter);
	}

	public static HashMap<String, Byte> lastInvisSent = new HashMap<String, Byte>();

	public void sendFlagPacket(Player player, int entID, Byte flag) throws InvocationTargetException {
		String uid = player.getName() + entID;
		if (lastInvisSent.containsKey(uid)) {
			if (lastInvisSent.get(uid).equals(flag))
				return;

		}
		lastInvisSent.put(uid, flag);

		//if (player.isOnline())
		//	return;
		
		if (Config.debugMessages == true)
			Logger.info("Sending flag "+flag+" on entity " + entID + " to " + player.getName());
		
		PacketContainer invisPacket = protocolManager.createPacket(Packets.Server.ENTITY_METADATA);

		ArrayList<WatchableObject> list = new ArrayList<WatchableObject>();
		list.add(new WatchableObject(0, 0, flag));

	
		invisPacket.getModifier().write(0, entID);
		invisPacket.getModifier().write(1, list);

		removePacketListener();
		protocolManager.sendServerPacket(player, invisPacket);
		addPacketListener();
	}

	private Byte getPacketFlag(WatchableObject data) {
		return (data.a() == 0) ? (Byte) data.b() : null;
	}

	public static HashMap<Entity, Integer> distanceTaskIDs = new HashMap<Entity, Integer>();

	int lastNum = 0;

	public class invisDistanceTask implements Runnable {
		private Entity entity;

		public invisDistanceTask(Entity entity) {
			this.entity = entity;
		}

		private int taskID;

		public void setId(int Id) {
			taskID = Id;
		}

		@Override
		public void run() {
			if (entity.isValid() == false) {
				getServer().getScheduler().cancelTask(taskID);
				return;
			}

			int radius = getServer().getViewDistance() * 16;
			List<Entity> ents = entity.getNearbyEntities(radius, radius, radius);

			for (Entity e : ents) {
				if (e instanceof Player) {
					// dist = e.getLocation().distance(entity.getLocation());

					if (Config.distanceEnabled == true && distanceView((Player) e, entity)) {
						try {
							sendFlagPacket((Player) e, entity.getEntityId(), defaultMask);
						} catch (InvocationTargetException e1) {
							// TODO Auto-generated catch block
							e1.printStackTrace();
						}

					} else if (canView((Player) e, entity) == false) {
							try {
								sendFlagPacket((Player) e, entity.getEntityId(), invisibleMask);
							} catch (InvocationTargetException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
							}
					}

				}
			}
		}
	}


	
	public void sendSurroundingInvisPackets(Player player) throws InvocationTargetException {
		int radius = getServer().getViewDistance() * 16;
		List<Entity> ents = player.getNearbyEntities(radius, radius, radius);
		String pName = player.getName();
		viewInvis.get(pName);

		for (Entity e : ents) {

			if (isInvisible(e)) {
				if (canView(player, e) == true) {
					sendFlagPacket(player, e.getEntityId(), defaultMask);
				} else {
					sendFlagPacket(player, e.getEntityId(), invisibleMask);
				}
			}

		}
	}

	public Boolean isInvisible(Entity entity) {
		if (entity instanceof CraftLivingEntity) {
			CraftLivingEntity cEnt = (CraftLivingEntity) entity;
			if (cEnt != null) {
				Collection<PotionEffect> collection = cEnt.getActivePotionEffects();
				if (collection != null && !collection.isEmpty()) {
					Iterator<PotionEffect> iterator = collection.iterator();
					while (iterator.hasNext()) {
						PotionEffect effect = (PotionEffect) iterator.next();
						if (effect.getType().getId() == invisEffect.getId()) 
							return true;
					}
				}
			}

		}

		return false;
	}

	public void fillViewInvis() {
		for (Player p : getServer().getOnlinePlayers()) 
			addPlayerInvisOps(p);
	}

	public void addPlayerInvisOps(Player player) {

		int flags = 0;
		if (Config.togglePlayerByDefault == true)
			flags = addMask(flags, maskPlayer);

		if (Config.toggleOtherByDefault == true)
			flags = addMask(flags, maskOther);

		viewInvis.put(player.getName(), flags);
	}

	public ChatColor colouredHasMask(int flags, int mask) {
		if (hasMask(flags, mask))
			return ChatColor.GREEN;
		
		return ChatColor.RED;
	}

	public static int maskPlayer = (int) Math.pow(2, 0);
	public static int maskOther = (int) Math.pow(2, 1);

	public static boolean hasMask(int flags, int mask) {
		return ((flags & mask) == mask);
	}

	public static int addMask(int flags, int mask) {
		return (flags |= mask);
	}

	public static int delMask(int flags, int mask) {
		return (flags &= ~mask);
	}

	public void sendMessage(CommandSender sender, String message, Boolean showConsole, Boolean sendPrefix) {
		if (sender instanceof Player && showConsole == true)
			Logger.info(sender.getName() + "->" + message);
		
		if (sendPrefix == true) {
			sender.sendMessage(chatPrefix + message);
		} else {
			sender.sendMessage(message);
		}
	}

	public void sendMessage(CommandSender sender, String message, Boolean showConsole) {
		sendMessage(sender, message, showConsole, true);
	}

	public void sendMessage(CommandSender sender, String message) {
		sendMessage(sender, message, true);
	}
}
