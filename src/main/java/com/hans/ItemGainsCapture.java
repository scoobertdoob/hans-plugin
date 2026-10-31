package com.hans;

import com.hans.ItemGainsLog.ArchiveFile;
import com.hans.ItemGainsLog.Gain;
import com.hans.ItemGainsLog.GainsFile;
import com.hans.RsbuddyData.Session;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.IntPredicate;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.Text;

/**
 * item_gains.json: a timed log of the items that entered the inventory, per play session (herbs picked, ores mined,
 * fish caught, items picked up), and of the items that left it (runes and items used up alching or crafting, food
 * eaten, items dropped). Each entry is one item's change on one game tick: time, item id and quantity, negative for
 * a loss. Raw values only; the app names and values them, and decides which losses matter.
 *
 * Not counted, as gains or as losses:
 * <ul>
 * <li>anything while a bank, deposit box, Grand Exchange, trade, shop or storage window is open, and for
 * {@link #COOLDOWN_TICKS} ticks after it closes (the window is seen through its widget, or through a change to the
 * container behind it);</li>
 * <li>equipping and unequipping, and filling or emptying a rune pouch or quiver: an item that left the worn equipment
 * or carried pouch this tick or the last arrives back in the inventory without counting, and one that entered it
 * leaves the inventory without counting ({@link ItemGainsTracker});</li>
 * <li>filling or emptying an essence pouch or the plank sack, read pouch by pouch from their count varbits every
 * tick; every essence kind is one key, since the counts do not say which kind they hold. Any essence or plank change
 * from the tick before such a move to the tick after it (across a bank visit, to the first recorded tick) records
 * nothing, whatever its amount ({@link ItemGainsTracker}). Essence or planks used while inside never touch the
 * inventory and are not counted. At a logout, an essence or plank change still held and a move whose window is still
 * open go to the quarantine instead of counting;</li>
 * <li>putting items into the seed box or tackle box or taking them out, read item by item from the boxes' containers
 * every tick and windowed the same way, item by item. A box's contents may be unknown until it is first opened or
 * checked: when they turn up, every item in it is a move, never a baseline for a gain or loss. While a box is carried
 * and its contents are not known (to the tick after they first arrive), and for {@link #MENU_TICKS} ticks after the
 * player uses the box from a menu (its contents may not be sent again), no change of an item it might hold counts
 * ({@link #mightHold}). While a box is carried or loaded, every change still held at a logout goes to the
 * quarantine;</li>
 * <li>filling or emptying a herb sack, gem bag, coal bag, log basket or fish barrel (any variant), or packing or
 * unpacking a sailing fish crate. The client is never sent their contents, so they are never read and no chat
 * message is relied on: while one is carried, in the inventory or worn, and on the tick after, and for
 * {@link #MENU_TICKS} ticks after any menu option on it, no change of an item it might hold counts (grimy herbs,
 * uncut gems, coal, logs and bark, raw fish: {@link #BOX_WORDS}). This only misses, never invents: such items gained,
 * used or dropped while the bag is carried are not logged. Items an open bag collects never reach the inventory, so
 * they are not logged either;</li>
 * <li>putting bolts into the bolt pouch, taking them out, or wielding them from it, read slot by slot from the pouch's
 * type and count varbits every tick (the game's enum {@link #BOLT_POUCH_ENUM} names each slot's bolt) and windowed
 * like the essence pouches, bolt by bolt. For {@link #MENU_TICKS} ticks after a menu option on the pouch, or while
 * its window is open, no change of a bolt it might hold counts, in case its counts are sent late; a count that moves
 * while its bolt cannot be named hides every item for its window;</li>
 * <li>the first readings after a login, which only set the baseline. After a world hop or region load on the same
 * account and mode, the containers are re-read and the changes still held are reconciled on the next tick, so a
 * pickup or skilling gain is still counted;</li>
 * <li>anything while no reading may be taken ({@link LoginState#reading}: before settle, or while the client reports
 * an unknown hash or another account or mode): nothing is read then, what was already decided is kept, the changes
 * still waiting for a worn or pouch transfer go to the plugin's quarantine instead of counting, and the next reading
 * starts a new baseline. The same at a change of key (a hop into another mode).</li>
 * </ul>
 * A region load or a hop to a world of the same mode, with the same account, is no such boundary: the changes held
 * one tick are reconciled on the next reading as on any tick, so a pickup is credited and an equip still cancels.
 *
 * Overlap with {@link LootCapture}: an NPC drop is written to loot.jsonl when it lands on the ground, and again here
 * (same item id and quantity, usually seconds later) when it is picked up. Drops never picked up are only in
 * loot.jsonl; skilling gains are only here. The app should de-duplicate by matching item id and quantity against
 * loot.jsonl lines shortly before.
 *
 * Changes are collected on the client thread and written every {@link #EVERY_TICKS} ticks and at logout. The live file
 * is bounded by {@link ItemGainsLog}, rotating older sessions and full chunks into archive files in the key's
 * folder before any entries are trimmed from item_gains.json. Each file names its account hash and mode. Uses
 * {@link SessionCapture}'s live session (so it is registered after it).
 */
final class ItemGainsCapture implements Capture
{
	static final String FILE = "item_gains.json";
	static final int COOLDOWN_TICKS = 2;
	private static final int QUIET_TICKS = LoginState.SETTLE_TICKS;
	private static final int EVERY_TICKS = 100; // ~1 min
	static final String UNRESOLVED = "inventory changes still waiting for their worn or pouch transfer, and transfers"
		+ " not yet matched, when readings stopped: never credited as gains or losses";

	/** Interfaces that move items between the inventory and somewhere else: neither a gain nor a loss. */
	private static final int[] TRANSFER_INTERFACES = {
		InterfaceID.BANKMAIN, InterfaceID.BANKSIDE, InterfaceID.BANK_DEPOSITBOX, InterfaceID.BANK_DEPOSIT_IMP,
		InterfaceID.SHARED_BANK, InterfaceID.SHARED_BANK_SIDE,
		InterfaceID.GE_OFFERS, InterfaceID.GE_OFFERS_SIDE, InterfaceID.GE_COLLECT, InterfaceID.GE_PRICECHECKER,
		InterfaceID.TRADEMAIN, InterfaceID.TRADECONFIRM, InterfaceID.TRADESIDE,
		InterfaceID.SHOPMAIN, InterfaceID.SHOPSIDE, InterfaceID.OMNISHOP_MAIN, InterfaceID.OMNISHOP_SIDE,
		InterfaceID.WILDERNESS_LOOTINGBAG, InterfaceID.SEED_VAULT, InterfaceID.SEED_VAULT_DEPOSIT,
		InterfaceID.POH_COSTUMES, InterfaceID.POH_COSTUMES_SIDE, InterfaceID.FARMING_TOOLS,
		InterfaceID.FARMING_TOOLS_SIDE, InterfaceID.CLANS_STORAGE_MAIN, InterfaceID.CLANS_STORAGE_SIDE,
		InterfaceID.RAIDS_STORAGE_PRIVATE, InterfaceID.RAIDS_STORAGE_SHARED, InterfaceID.RAIDS_STORAGE_SIDE,
		InterfaceID.HOSIDIUS_SEEDBOX, InterfaceID.TACKLE_BOX_MAIN, InterfaceID.TACKLE_BOX_SIDE,
		InterfaceID.FORESTRY_KIT_MAIN, InterfaceID.FORESTRY_KIT_SIDE,
		InterfaceID.HUNTSMANS_KIT, InterfaceID.HUNTSMANS_KIT_SIDE,
		InterfaceID.PREPOT_DEVICE,
	};
	/** Containers whose change means items are moving between them and the inventory. */
	private static final int[] TRANSFER_CONTAINERS = {
		InventoryID.BANK, InventoryID.TRADEOFFER, InventoryID.SEED_VAULT, InventoryID.LOOTING_BAG,
		InventoryID.POH_COSTUMES, InventoryID.INV_GROUP_TEMP,
		InventoryID.FORESTRY_KIT, InventoryID.HUNTSMANS_KIT, InventoryID.PREPOT_DEVICE_INV,
	};
	/** Carried boxes read item by item, as counted stores ({@link #readStores}): the seed box and the tackle box. */
	private static final int[] ITEM_STORES = {InventoryID.SEED_BOX, InventoryID.TACKLE_BOX};
	/**
	 * Each box's own items (the seed box closed and open), and after the {@link #ITEM_STORES} boxes the skilling bags
	 * whose contents the client is never sent (#69 group 3): the herb sack (and silk-lined), gem bag (and the gem pouch,
	 * satchel, tote and sack upgrades), coal bag, log basket (and forestry basket), fish barrel (and fish sack
	 * barrel), open and closed, and the sailing fish crates, empty or packed (fish can be packed into one from the
	 * inventory as well as the cargo hold). No container, count variable
	 * or enum shows what they hold, so a bag is a box that never loads: while it is carried, in the inventory or worn,
	 * and on the tick after, no change of an item it might hold counts. The ids are RuneLite's own variants of each bag
	 * ({@code item_variations.json}), the dummies included.
	 */
	private static final int[][] BOX_ITEMS = {{ItemID.SEED_BOX, ItemID.SEED_BOX_OPEN}, {ItemID.TACKLE_BOX},
		{ItemID.SLAYER_HERB_SACK, ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK_SILK,
			ItemID.SLAYER_HERB_SACK_SILK_OPEN},
		{ItemID.GEM_BAG, ItemID.GEM_BAG_OPEN, ItemID.GEM_BAG_DUMMY, ItemID.GEM_POUCH, ItemID.GEM_POUCH_OPEN,
			ItemID.GEM_SATCHEL, ItemID.GEM_SATCHEL_OPEN, ItemID.GEM_TOTE, ItemID.GEM_TOTE_OPEN, ItemID.GEM_SACK,
			ItemID.GEM_SACK_OPEN},
		{ItemID.COAL_BAG, ItemID.COAL_BAG_OPEN, ItemID.COAL_BAG_DUMMY},
		{ItemID.LOG_BASKET_CLOSED, ItemID.LOG_BASKET_OPEN, ItemID.FORESTRY_BASKET_CLOSED, ItemID.FORESTRY_BASKET_OPEN},
		{ItemID.FISH_BARREL_CLOSED, ItemID.FISH_BARREL_OPEN, ItemID.FISH_SACK_BARREL_CLOSED,
			ItemID.FISH_SACK_BARREL_OPEN},
		{ItemID.FISH_CRATE_EMPTY, ItemID.FISH_CRATE_GIANT_KRILL, ItemID.FISH_CRATE_GIANT_KRILL_VAR,
			ItemID.FISH_CRATE_HADDOCK, ItemID.FISH_CRATE_HADDOCK_VAR, ItemID.FISH_CRATE_YELLOWFIN,
			ItemID.FISH_CRATE_YELLOWFIN_VAR, ItemID.FISH_CRATE_HALIBUT, ItemID.FISH_CRATE_HALIBUT_VAR,
			ItemID.FISH_CRATE_BLUEFIN, ItemID.FISH_CRATE_BLUEFIN_VAR, ItemID.FISH_CRATE_MARLIN,
			ItemID.FISH_CRATE_MARLIN_VAR},
	};
	/** Each box's names as a menu shows them, lower case ("Open seed box" and "Silk-lined herb sack" contain them). */
	private static final String[][] BOX_NAMES = {{"seed box"}, {"tackle box"}, {"herb sack"},
		{"gem bag", "gem pouch", "gem satchel", "gem tote", "gem sack"}, {"coal bag"}, {"log basket", "forestry basket"},
		{"fish barrel", "fish sack barrel"}, {"fish crate"}};
	/**
	 * The words naming an item each box might hold: seeds, acorns and spores; fishing gear, outfits and baits; grimy
	 * herbs; uncut gems; coal; logs and bark; raw fish (the barrel and the crates alike). Not checked against the
	 * game's own rules, so they are generous: a match only stops an item counting while its box cannot be read. The
	 * seed box and the bags have nothing else to go by; for the tackle box they only add to the game's own list
	 * ({@link #TACKLE_BOX_ENUM}). A bag's own items never match it (the coal bag is not coal). An item with no name
	 * matches every box.
	 */
	private static final String[][] BOX_WORDS = {
		{"seed", "seeds", "acorn", "spore", "spores"},
		{"rod", "rods", "net", "harpoon", "pot", "vessel", "angler", "fishing", "tackle", "barrel", "bait", "feather",
			"feathers", "sandworms", "worms", "offcuts", "chunks", "roe", "caviar", "karambwanji", "flakes", "minerals"},
		{"grimy"},
		{"uncut"},
		{"coal"},
		{"logs", "bark"},
		{"raw", "leaping", "eel", "bluegill", "tench", "siren", "karambwanji"},
		{"raw", "leaping", "eel", "bluegill", "tench", "siren", "karambwanji"},
	};
	/**
	 * The game's enum of the items the tackle box takes (its deposit script reads it), including flippers, the Molch
	 * pearl and the Merfolk trident, which no name word catches.
	 */
	static final int TACKLE_BOX_ENUM = 3702;
	/** Ticks after a menu option on a box during which its contents are not trusted. */
	static final int MENU_TICKS = 3;
	/** The rune pouch's slots: each slot's rune, as a key of {@code EnumID.RUNEPOUCH_RUNE}, and its count. */
	static final int[] RUNE_POUCH_TYPES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	static final int[] RUNE_POUCH_QUANTITIES = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
	};
	/** The essence pouches' counts, small to colossal (a degraded pouch keeps its varbit). */
	private static final int[] ESSENCE_POUCHES = {
		VarbitID.SMALL_ESSENCE_POUCH, VarbitID.MEDIUM_ESSENCE_POUCH, VarbitID.LARGE_ESSENCE_POUCH,
		VarbitID.GIANT_ESSENCE_POUCH, VarbitID.COLOSSAL_ESSENCE_POUCH,
	};
	/** Each pouch's kind of essence (only compared, never decoded: the values are not checked in game). */
	private static final int[] ESSENCE_POUCH_TYPES = {
		VarbitID.SMALL_ESSENCE_POUCH_TYPE, VarbitID.MEDIUM_ESSENCE_POUCH_TYPE, VarbitID.LARGE_ESSENCE_POUCH_TYPE,
		VarbitID.GIANT_ESSENCE_POUCH_TYPE, VarbitID.COLOSSAL_ESSENCE_POUCH_TYPE,
	};
	/** Every essence a pouch can hold: they share one store key, {@link #ESSENCE}. */
	private static final int[] ESSENCES = {
		ItemID.BLANKRUNE, ItemID.BLANKRUNE_HIGH, ItemID.BLANKRUNE_DAEYALT, ItemID.GOTR_GUARDIAN_ESSENCE,
	};
	/** The store key of all essence, named by pure essence. */
	static final int ESSENCE = ItemID.BLANKRUNE_HIGH;
	/** The plank sack's count of each plank, and the plank it counts. */
	private static final int[] PLANK_SACK = {
		VarbitID.PLANK_SACK_PLAIN, VarbitID.PLANK_SACK_OAK, VarbitID.PLANK_SACK_TEAK, VarbitID.PLANK_SACK_MAHOGANY,
		VarbitID.PLANK_SACK_CAMPHOR, VarbitID.PLANK_SACK_IRONWOOD, VarbitID.PLANK_SACK_ROSEWOOD,
	};
	private static final int[] PLANKS = {
		ItemID.WOODPLANK, ItemID.PLANK_OAK, ItemID.PLANK_TEAK, ItemID.PLANK_MAHOGANY,
		ItemID.PLANK_CAMPHOR, ItemID.PLANK_IRONWOOD, ItemID.PLANK_ROSEWOOD,
	};
	/**
	 * The bolt pouch's four slots: each slot's bolt, as a key of {@link #BOLT_POUCH_ENUM}, and its count (8 and 14 bits of
	 * varps 834, 835, 1598 and 1599). Read every tick, carried or not.
	 */
	private static final int[] BOLT_POUCH_TYPES = {
		VarbitID.XBOWS_POUCH_SLOT1, VarbitID.XBOWS_POUCH_SLOT2, VarbitID.XBOWS_POUCH_SLOT3, VarbitID.XBOWS_POUCH_SLOT4,
	};
	private static final int[] BOLT_POUCH_COUNTS = {
		VarbitID.XBOWS_POUCH_NUM1, VarbitID.XBOWS_POUCH_NUM2, VarbitID.XBOWS_POUCH_NUM3, VarbitID.XBOWS_POUCH_NUM4,
	};
	/**
	 * The game's enum from a bolt pouch slot's type to its bolt (87 kinds, bolt racks included), as the pouch's own
	 * client script (4047) reads it; enum 337 is the reverse.
	 */
	static final int BOLT_POUCH_ENUM = 336;
	/** The store key of a bolt pouch slot whose bolt cannot be named (its enum not read); no item has it. */
	private static final int UNKNOWN_BOLT = Integer.MIN_VALUE;
	private static final IntPredicate ANY_ITEM = id -> true;
	private static final int[] POUCH_VARPS = {
		VarPlayerID.RUNE_POUCH_A, VarPlayerID.RUNE_POUCH_B, VarPlayerID.RUNE_POUCH_C, VarPlayerID.RUNE_POUCH_D,
		VarPlayerID.RUNE_POUCH_K, VarPlayerID.RUNE_POUCH_L, VarPlayerID.RUNE_POUCH_M,
		VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO, VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT,
	};

	private final CaptureContext ctx;
	private final SessionCapture sessions;
	private final ItemGainsTracker tracker;
	private final ItemGainsLog gainsLog = new ItemGainsLog(); // touched only on the executor
	private final List<Gain> pending = new ArrayList<>();
	private ArchiveFile pendingArchive; // touched only on the executor
	private volatile boolean retryArchive;
	private int archiveSeq;
	private boolean inventoryDirty = true;
	private boolean wornDirty = true;
	private boolean pouchDirty = true;
	private boolean transferChanged;
	private int tick;
	private int lastTransferTick = Integer.MIN_VALUE / 2;
	private int quietUntil;
	private int ticksSince;
	private boolean loadFirst = true;
	private String sessionStart;
	private final Map<Integer, Integer> sessionNet = new HashMap<>(); // this session's net change per item, for the upload
	private String netStart; // the session sessionNet belongs to
	private Instant lastTick;
	private boolean paused = true; // no reading was taken on the last tick: the next one is a new baseline
	// per box and bag: in the last inventory reading, and in the last worn reading; carried (either) on the last reading,
	// and on the tick before; contents loaded on the last reading, and on the tick before (never, for a bag); the last
	// tick on which a menu option on it keeps its contents untrusted
	private final boolean[] boxInInventory = new boolean[BOX_ITEMS.length];
	private final boolean[] boxWorn = new boolean[BOX_ITEMS.length];
	private final boolean[] boxCarried = new boolean[BOX_ITEMS.length];
	private final boolean[] boxCarriedBefore = new boolean[BOX_ITEMS.length];
	private final boolean[] boxKnown = new boolean[BOX_ITEMS.length];
	private final boolean[] boxKnownBefore = new boolean[BOX_ITEMS.length];
	private final int[] boxUsedUntil = new int[BOX_ITEMS.length];
	private final IntPredicate[] boxHolds = new IntPredicate[BOX_ITEMS.length];
	private final Map<Integer, Integer> holdsCache = new HashMap<>(); // canonical id to a bit per box its name fits
	private Set<Integer> tackleItems; // equivalent ids of TACKLE_BOX_ENUM, once read (client thread)
	// the bolt pouch: BOLT_POUCH_ENUM once read (slot type to bolt, and the bolts' equivalent ids); each slot's last
	// type and count, and whether its bolt was named; a slot whose bolt could not be named moved this tick; the last
	// tick on which a menu option on the pouch keeps its counts untrusted
	private Map<Integer, Integer> boltsByType;
	private Set<Integer> boltItems;
	private final int[] boltSlotBefore = new int[BOLT_POUCH_COUNTS.length];
	private final boolean[] boltNamedBefore = new boolean[BOLT_POUCH_COUNTS.length];
	private boolean boltUnnamedMoved;
	private int boltUsedUntil;
	private final IntPredicate boltHolds = this::mightHoldBolt;

	ItemGainsCapture(CaptureContext ctx, SessionCapture sessions)
	{
		this.ctx = ctx;
		this.sessions = sessions;
		this.tracker = new ItemGainsTracker(this::equivalentId, this::consumable, ItemGainsCapture::storeKey);
		for (int b = 0; b < BOX_ITEMS.length; b++)
		{
			final int box = b;
			boxHolds[b] = id -> mightHold(box, id);
		}
		Arrays.fill(boltNamedBefore, true);
	}

	/** The key a counted skilling store counts an item under: {@link #ESSENCE} for every essence, else the item. */
	static int storeKey(int equivalentId)
	{
		for (int e : ESSENCES)
		{
			if (e == equivalentId)
			{
				return ESSENCE;
			}
		}
		return equivalentId;
	}

	/** The unnoted, non-placeholder form of an item. */
	private int canonicalId(int id)
	{
		return ctx.itemManager != null ? ctx.itemManager.canonicalize(id) : id;
	}

	private int equivalentId(int id)
	{
		return ItemVariationMapping.map(canonicalId(id));
	}

	/** True when the item has an Eat or Drink inventory option (doses/slices are consumed, not degraded gear). */
	private boolean consumable(int id)
	{
		if (ctx.itemManager == null)
		{
			return false;
		}
		ItemComposition comp = ctx.itemManager.getItemComposition(id);
		if (comp == null)
		{
			return false;
		}
		String[] actions = comp.getInventoryActions();
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if ("Eat".equals(a) || "Drink".equals(a))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public void onLogin()
	{
		// also after a world hop or region load: the containers may be sent again, so read them afresh. What is held
		// stays held: the next tick's reading (same account and mode, or there is none) reconciles it as on any tick
		inventoryDirty = true;
		wornDirty = true;
		pouchDirty = true;
		quietUntil = tick + QUIET_TICKS;
	}

	@Override
	public void onSettled(Player local, Instant now)
	{
		Session live = sessions.live();
		sessionStart = live == null ? now.toString() : live.start;
		if (!sessionStart.equals(netStart))
		{
			sessionNet.clear();
			netStart = sessionStart;
		}
		loadFirst = true;
	}

	@Override
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId() & ~0x8000;
		if (id == InventoryID.INV)
		{
			inventoryDirty = true;
		}
		else if (id == InventoryID.WORN)
		{
			wornDirty = true;
		}
		else
		{
			for (int c : TRANSFER_CONTAINERS)
			{
				transferChanged |= c == id;
			}
		}
	}

	/**
	 * Any menu option on a seed box, tackle box or skilling bag, or with one as its target (an item used on it, or it
	 * used on something): its contents may change without being sent again, so for {@link #MENU_TICKS} ticks they are
	 * not trusted, carried or not (Fill, Empty, Check, or a bag emptied as it is dropped or put away). The same for the
	 * bolt pouch, and for any option in its window.
	 */
	@Override
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String target = event.getMenuTarget() == null ? ""
			: Text.removeTags(event.getMenuTarget()).toLowerCase(Locale.ROOT);
		for (int b = 0; b < BOX_ITEMS.length; b++)
		{
			boolean onBox = false;
			for (String name : BOX_NAMES[b])
			{
				onBox |= target.contains(name);
			}
			for (int item : BOX_ITEMS[b])
			{
				onBox |= event.getItemId() == item;
			}
			if (onBox)
			{
				boxUsedUntil[b] = tick + 1 + MENU_TICKS;
			}
		}
		// the bolt pouch's own options, bolts used on it, and its window's buttons (Withdraw, Wield, Remove)
		if (target.contains("bolt pouch") || event.getItemId() == ItemID.XBOWS_BOLT_POUCH
			|| WidgetUtil.componentToInterface(event.getParam1()) == InterfaceID.XBOWS_POUCH)
		{
			boltUsedUntil = tick + 1 + MENU_TICKS;
		}
	}

	@Override
	public void onVarbitChanged(VarbitChanged event)
	{
		int varbit = event.getVarbitId();
		int varp = event.getVarpId();
		for (int b : RUNE_POUCH_TYPES)
		{
			pouchDirty |= b == varbit;
		}
		for (int b : RUNE_POUCH_QUANTITIES)
		{
			pouchDirty |= b == varbit;
		}
		for (int p : POUCH_VARPS)
		{
			pouchDirty |= p == varp;
		}
	}

	@Override
	public void onGameTick(Player local, Instant now)
	{
		tick++;
		if (ctx.login.reading() == null)
		{
			pause(now);
			return;
		}
		paused = false;
		if (transferChanged || transferOpen())
		{
			lastTransferTick = tick;
		}
		transferChanged = false;

		Map<Integer, Integer> inventory = inventoryDirty ? read(InventoryID.INV) : null;
		Map<Integer, Integer> worn = wornDirty ? read(InventoryID.WORN) : null;
		Map<Integer, Integer> pouch = pouchDirty ? readPouch() : null;
		Map<Integer, ItemGainsTracker.Slot> store = readStores();
		for (int b = 0; b < BOX_ITEMS.length; b++)
		{
			if (inventory != null)
			{
				boxInInventory[b] = holdsAny(inventory, BOX_ITEMS[b]);
			}
			if (worn != null)
			{
				boxWorn[b] = holdsAny(worn, BOX_ITEMS[b]); // the forestry basket and fish sack barrel can be worn
			}
			boxCarried[b] = boxInInventory[b] || boxWorn[b];
			// contents unknown up to the last reading (so also on the tick they first arrive; a bag's, always while it
			// is carried and on the tick after), or just used from a menu
			if ((boxCarried[b] || boxCarriedBefore[b]) && !boxKnownBefore[b] || tick <= boxUsedUntil[b])
			{
				tracker.blind(boxHolds[b]);
			}
			boxCarriedBefore[b] = boxCarried[b];
			boxKnownBefore[b] = boxKnown[b];
		}
		// the bolt pouch just used from a menu (its counts may come late), its window open, or a count moving whose
		// bolt has no name
		if (tick <= boltUsedUntil || boltPouchOpen())
		{
			tracker.blind(boltHolds);
		}
		if (boltUnnamedMoved)
		{
			tracker.blind(ANY_ITEM);
		}
		inventoryDirty &= inventory == null; // a container not loaded yet is read again next tick
		wornDirty &= worn == null;
		pouchDirty &= pouch == null;
		boolean record = sessionStart != null && ctx.keeps(ctx.config.exportItems())
			&& tick > quietUntil && tick - lastTransferTick > COOLDOWN_TICKS;
		String time = now.toString();
		lastTick = now;
		for (Map.Entry<Integer, Integer> g : tracker.tick(inventory, worn, pouch, store, record).entrySet())
		{
			pending.add(new Gain(time, g.getKey(), g.getValue()));
			sessionNet.merge(g.getKey(), g.getValue(), Integer::sum);
		}

		if (++ticksSince >= EVERY_TICKS)
		{
			ticksSince = 0;
			write(time);
		}
	}

	/**
	 * A tick on which no reading may be taken: the client may be showing another account. The gains and losses
	 * already decided stay pending for this login's key, and the containers are read afresh, as a new baseline, once a
	 * reading may be taken again.
	 */
	private void pause(Instant now)
	{
		if (paused)
		{
			return;
		}
		paused = true;
		rebaseline(now);
	}

	/**
	 * A boundary after which no reading follows this baseline for the same account and mode (a paused reading, a
	 * change of key); not a region load or a same-mode hop, which {@link #onLogin} reads straight through. The
	 * changes still waiting for their transfer half are never credited: the worn or pouch update that would cancel
	 * them may arrive only after the boundary, so equipping an arrow would read as a loss. They go to the plugin's
	 * quarantine, with why, and the next reading is a new baseline. Missing a real change held at the boundary is
	 * accepted; a false gain or loss is not. The changes already decided stay in {@link #pending}.
	 */
	private void rebaseline(Instant now)
	{
		quarantine(now, tracker.unresolved(), LoginState.CHANGING_WORLD);
		inventoryDirty = true;
		wornDirty = true;
		pouchDirty = true;
	}

	/** Writes the open transfer candidates to the plugin's quarantine, with why; nothing when there are none. */
	private void quarantine(Instant now, Map<String, Map<Integer, Integer>> open, String defaultWhy)
	{
		if (open.isEmpty())
		{
			return;
		}
		LoginState.Token token = ctx.login.token();
		Map<String, Object> value = new LinkedHashMap<>(open);
		value.put("last_tick", lastTick == null ? null : lastTick.toString());
		value.put("session_start", sessionStart);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kept_at", now.toString());
		m.put("kind", "item_gains");
		m.put("why", token.why != null ? token.why : defaultWhy);
		m.put("reason", UNRESOLVED);
		m.put("token", token.seq);
		m.putAll(ctx.login.whereabouts());
		m.put("value", value);
		ctx.json.appendQuarantine(m);
	}

	@Override
	public Future<?> onLogout(Instant now)
	{
		if (ctx.login.keyChanged())
		{
			rebaseline(now); // a hop into another mode or account: the held changes' other half may come only there
		}
		else
		{
			// an essence or plank change still held, or a fill or empty not yet met, could be decided only on a tick
			// that never follows: quarantined, never a gain or loss. With a seed box or tackle box carried or loaded,
			// any held change could be one going into it, so all of them are. The other held changes are flushed
			quarantine(now, tracker.storeUnresolved(anyBox()), LoginState.LOGGED_OUT);
		}
		String time = flushTime(now);
		for (Map.Entry<Integer, Integer> g : tracker.flush().entrySet())
		{
			pending.add(new Gain(time, g.getKey(), g.getValue()));
			sessionNet.merge(g.getKey(), g.getValue(), Integer::sum);
		}
		Future<?> done = write(now.toString());
		pending.clear(); // whatever could not be written stays with this login: never credited to the next key
		tracker.reset();
		sessionStart = null;
		lastTick = null;
		paused = true;
		for (int b = 0; b < BOX_ITEMS.length; b++)
		{
			boxInInventory[b] = false;
			boxWorn[b] = false;
			boxCarried[b] = false;
			boxCarriedBefore[b] = false;
			boxKnown[b] = false;
			boxKnownBefore[b] = false;
			boxUsedUntil[b] = 0;
		}
		Arrays.fill(boltSlotBefore, 0);
		Arrays.fill(boltNamedBefore, true);
		boltUsedUntil = 0;
		return done;
	}

	/**
	 * The time for the changes still held at a logout or world hop: after the last tick, so a held change never shares
	 * its time with the same change emitted on that tick (the importer would take the two for one copy).
	 */
	private String flushTime(Instant now)
	{
		if (lastTick != null && !now.isAfter(lastTick))
		{
			now = lastTick.plusMillis(1);
		}
		return now.toString();
	}

	/** True while one of the transfer interfaces is showing. */
	private boolean transferOpen()
	{
		for (int group : TRANSFER_INTERFACES)
		{
			Widget w = ctx.client.getWidget(group, 0);
			if (w != null && !w.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	/** True while the bolt pouch's window is showing. */
	private boolean boltPouchOpen()
	{
		Widget w = ctx.client.getWidget(InterfaceID.XBOWS_POUCH, 0);
		return w != null && !w.isHidden();
	}

	private Map<Integer, Integer> read(int containerId)
	{
		ItemContainer container = ctx.client.getItemContainer(containerId);
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		int[] ids = new int[items.length];
		int[] qtys = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qtys[i] = items[i].getQuantity();
		}
		return ItemGainsTracker.totals(ids, qtys);
	}

	/** The rune of a rune pouch slot's type (a key of {@code EnumID.RUNEPOUCH_RUNE}); 0 or less when none. */
	static int pouchRune(EnumComposition runeEnum, int type)
	{
		return type > 0 ? runeEnum.getIntValue(type) : -1;
	}

	/** Reads the rune pouch and Dizana's quiver contents from their game variables on the client thread. */
	private Map<Integer, Integer> readPouch()
	{
		EnumComposition runeEnum = ctx.client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runeEnum == null)
		{
			return null;
		}
		Map<Integer, Integer> out = new HashMap<>();
		for (int i = 0; i < RUNE_POUCH_TYPES.length; i++)
		{
			int type = ctx.client.getVarbitValue(RUNE_POUCH_TYPES[i]);
			int qty = ctx.client.getVarbitValue(RUNE_POUCH_QUANTITIES[i]);
			if (type > 0 && qty > 0)
			{
				int itemId = pouchRune(runeEnum, type);
				if (itemId > 0)
				{
					out.merge(itemId, qty, Integer::sum);
				}
			}
		}
		int quiverAmmo = ctx.client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		int quiverQty = ctx.client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
		if (quiverAmmo > 0 && quiverQty > 0)
		{
			out.merge(quiverAmmo, quiverQty, Integer::sum);
		}
		return out;
	}

	/**
	 * Reads the essence pouches' and the plank sack's counts on the client thread, one slot per pouch and per plank
	 * kind (keyed by its count varbit), so the tracker sees each pouch's fill or empty before they are added up. Read
	 * every tick rather than on a varbit event: a few dozen varbit reads, and no dependence on which event a packed
	 * varp sends. The first reading after a login or a boundary is only the tracker's new baseline, and a login's
	 * quiet ticks absorb counts that arrive a few ticks late.
	 *
	 * The bolt pouch is read the same way from its four slots' type and count varbits, each slot's bolt named by
	 * {@link #BOLT_POUCH_ENUM}. The seed box and the tackle box are read from their item containers, one slot per box
	 * and store key. A box may not be loaded until the player first opens or checks it: its slots are then absent, and
	 * when the box turns up (or goes) every item in it is a move, so a fill on the tick it first loads records nothing.
	 */
	private Map<Integer, ItemGainsTracker.Slot> readStores()
	{
		Map<Integer, ItemGainsTracker.Slot> out = new HashMap<>();
		for (int i = 0; i < ESSENCE_POUCHES.length; i++)
		{
			int qty = Math.max(0, ctx.client.getVarbitValue(ESSENCE_POUCHES[i]));
			int kind = ctx.client.getVarbitValue(ESSENCE_POUCH_TYPES[i]);
			out.put(ESSENCE_POUCHES[i], new ItemGainsTracker.Slot(ESSENCE, qty, kind));
		}
		for (int i = 0; i < PLANK_SACK.length; i++)
		{
			int qty = Math.max(0, ctx.client.getVarbitValue(PLANK_SACK[i]));
			out.put(PLANK_SACK[i], new ItemGainsTracker.Slot(PLANKS[i], qty, 0));
		}
		Map<Integer, Integer> bolts = boltsByType();
		boltUnnamedMoved = false;
		for (int i = 0; i < BOLT_POUCH_COUNTS.length; i++)
		{
			// one slot per pouch slot (keyed by its count varbit), its type as the kind: a slot taking another bolt
			// while it holds some is an empty and a fill. An empty slot is left out (its type may linger)
			int type = ctx.client.getVarbitValue(BOLT_POUCH_TYPES[i]);
			int qty = Math.max(0, ctx.client.getVarbitValue(BOLT_POUCH_COUNTS[i]));
			Integer bolt = bolts == null ? null : bolts.get(type);
			boolean named = qty == 0 || bolt != null;
			int slot = type << 16 | qty;
			boltUnnamedMoved |= slot != boltSlotBefore[i] && !(named && boltNamedBefore[i]);
			boltSlotBefore[i] = slot;
			boltNamedBefore[i] = named;
			if (qty > 0)
			{
				int key = bolt != null ? storeKey(equivalentId(bolt)) : UNKNOWN_BOLT;
				out.put(BOLT_POUCH_COUNTS[i], new ItemGainsTracker.Slot(key, qty, type));
			}
		}
		for (int b = 0; b < ITEM_STORES.length; b++)
		{
			// the plain and the flagged id both, each with its own slots: a change in either is a move, and two copies
			// of one move open the same window
			boxKnown[b] = false;
			for (int flagged = 0; flagged < 2; flagged++)
			{
				Map<Integer, Integer> box = read(ITEM_STORES[b] | flagged * 0x8000);
				if (box == null)
				{
					continue;
				}
				boxKnown[b] = true;
				Map<Integer, Integer> byKey = new HashMap<>();
				box.forEach((id, qty) -> byKey.merge(storeKey(equivalentId(id)), qty, Integer::sum));
				for (Map.Entry<Integer, Integer> e : byKey.entrySet())
				{
					out.put(itemSlot(b * 2 + flagged, e.getKey()),
						new ItemGainsTracker.Slot(e.getKey(), e.getValue(), 0));
				}
			}
		}
		return out;
	}

	/**
	 * True when a seed box or tackle box is carried or its contents are loaded. Not the bags: they take only the kinds
	 * of item their list names, whose changes are never held while a bag is carried, so nothing else needs setting aside.
	 */
	private boolean anyBox()
	{
		for (int b = 0; b < ITEM_STORES.length; b++)
		{
			if (boxCarried[b] || boxKnown[b])
			{
				return true;
			}
		}
		return false;
	}

	/** True when the container holds any of the items. */
	private static boolean holdsAny(Map<Integer, Integer> container, int[] items)
	{
		for (int item : items)
		{
			if (container.containsKey(item))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True when the box might hold the item. For the tackle box: listed in the game's enum, or named by a tackle word;
	 * while the enum cannot be read, every item. For the seed box and the bags: named by one of its words
	 * ({@link #BOX_WORDS}), and for a bag not one of its own items. Client thread.
	 */
	private boolean mightHold(int box, int id)
	{
		if (box == 1)
		{
			Set<Integer> listed = tackleItems();
			if (listed == null || listed.contains(equivalentId(id)))
			{
				return true;
			}
		}
		int canonical = canonicalId(id);
		Integer bits = holdsCache.get(canonical);
		if (bits == null)
		{
			ItemComposition comp = ctx.client.getItemDefinition(canonical);
			String name = comp == null ? null : comp.getName();
			bits = 0;
			for (int b = 0; b < BOX_WORDS.length; b++)
			{
				if ((name == null || namedBy(name, BOX_WORDS[b])) && !(b >= ITEM_STORES.length && ownItem(b, canonical)))
				{
					bits |= 1 << b;
				}
			}
			holdsCache.put(canonical, bits);
		}
		return (bits & 1 << box) != 0;
	}

	/**
	 * The tackle box's items from {@link #TACKLE_BOX_ENUM}, read once; null while it cannot be read or is empty (tried
	 * again next time). Its values are taken as items, and its keys too unless they only number the entries 0, 1, 2...
	 */
	private Set<Integer> tackleItems()
	{
		if (tackleItems != null)
		{
			return tackleItems;
		}
		EnumComposition list = ctx.client.getEnum(TACKLE_BOX_ENUM);
		if (list == null)
		{
			return null;
		}
		int[] keys = list.getKeys();
		int[] values = list.getIntVals();
		boolean numbered = keys != null;
		for (int i = 0; keys != null && i < keys.length; i++)
		{
			numbered &= keys[i] == i;
		}
		Set<Integer> out = new HashSet<>();
		for (int[] ids : new int[][] {numbered ? null : keys, values})
		{
			for (int i = 0; ids != null && i < ids.length; i++)
			{
				if (ids[i] > 0)
				{
					out.add(equivalentId(ids[i]));
				}
			}
		}
		if (!out.isEmpty())
		{
			tackleItems = out;
		}
		return tackleItems;
	}

	/**
	 * {@link #BOLT_POUCH_ENUM}, read once: a slot's type to its bolt; null while it cannot be read or is empty (tried
	 * again next time). Client thread.
	 */
	private Map<Integer, Integer> boltsByType()
	{
		if (boltsByType != null)
		{
			return boltsByType;
		}
		EnumComposition list = ctx.client.getEnum(BOLT_POUCH_ENUM);
		int[] keys = list == null ? null : list.getKeys();
		int[] values = list == null ? null : list.getIntVals();
		if (keys == null || values == null)
		{
			return null;
		}
		Map<Integer, Integer> byType = new HashMap<>();
		Set<Integer> items = new HashSet<>();
		for (int i = 0; i < keys.length && i < values.length; i++)
		{
			if (keys[i] > 0 && values[i] > 0)
			{
				byType.put(keys[i], values[i]);
				items.add(equivalentId(values[i]));
			}
		}
		if (!byType.isEmpty())
		{
			boltsByType = byType;
			boltItems = items;
		}
		return boltsByType;
	}

	/** True when the bolt pouch might hold the item: one of the game's pouch bolts, or every item while unread. */
	private boolean mightHoldBolt(int id)
	{
		return boltsByType() == null || boltItems.contains(equivalentId(id));
	}

	/** True when the item is one of the box's own items, in any of their forms. */
	private boolean ownItem(int box, int canonical)
	{
		int eq = ItemVariationMapping.map(canonical);
		for (int own : BOX_ITEMS[box])
		{
			if (own == canonical || ItemVariationMapping.map(own) == eq)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean namedBy(String name, String[] words)
	{
		for (String part : name.toLowerCase(Locale.ROOT).split("[^a-z]+"))
		{
			for (String w : words)
			{
				if (w.equals(part))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** A box item's slot id: negative, so it never meets a count varbit's id; item ids stay below 2^24. */
	private static int itemSlot(int box, int key)
	{
		return -1 - (box << 24 | key);
	}

	/**
	 * This session's net change per item so far (a copy), or null before a session started. The map is keyed by the
	 * session's start, as in sessions.json. Client thread.
	 */
	Map.Entry<String, Map<Integer, Integer>> sessionNet()
	{
		return netStart == null ? null : new AbstractMap.SimpleImmutableEntry<>(netStart,
			new HashMap<>(sessionNet));
	}

	/**
	 * Hands the pending changes to the executor. When rotation is needed, the archive file is written on the executor
	 * first and verified on disk before {@link ItemGainsLog} trims those entries from {@code item_gains.json}.
	 */
	private Future<?> write(String stamp)
	{
		final ProfileKey key = ctx.login.logoutKey(); // pending holds only changes read under this login's key
		if (!ctx.writes(ctx.config.exportItems()))
		{
			pending.clear(); // no file wanted ("Write files" or the items switch is off): nothing is kept for later
			return null;
		}
		if ((pending.isEmpty() && !retryArchive) || key == null || sessionStart == null)
		{
			return null;
		}
		final List<Gain> batch = new ArrayList<>(pending);
		pending.clear();
		final String start = sessionStart;
		final boolean load = loadFirst;
		loadFirst = false;
		final String archName = ItemGainsLog.archiveFileName(stamp, ++archiveSeq);

		ctx.json.writeLater(key, archName, () ->
		{
			if (load)
			{
				gainsLog.load(key.toString(), ctx.json.read(key, FILE, GainsFile.class));
			}
			if (!batch.isEmpty())
			{
				gainsLog.add(start, batch);
			}
			pendingArchive = gainsLog.planArchive(key.name, stamp, archName);
			return pendingArchive == null ? null : tagged(pendingArchive.file, key);
		});

		return ctx.json.writeLater(key, FILE, () ->
		{
			if (pendingArchive != null)
			{
				GainsFile persisted = ctx.json.read(key, pendingArchive.fileName, GainsFile.class);
				if (ItemGainsLog.archiveMatches(pendingArchive.file, persisted))
				{
					gainsLog.commitArchive(pendingArchive);
					retryArchive = false;
				}
				else
				{
					retryArchive = true;
				}
				pendingArchive = null;
			}
			return tagged(gainsLog.compose(key.name, stamp), key);
		});
	}

	/** Names the file's account hash and mode (additive fields the importer checks against the folder). */
	static GainsFile tagged(GainsFile f, ProfileKey key)
	{
		f.accountHash = key.hashText();
		f.mode = key.mode;
		return f;
	}
}
