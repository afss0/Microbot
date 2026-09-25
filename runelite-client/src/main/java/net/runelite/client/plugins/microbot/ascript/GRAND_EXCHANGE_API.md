# Grand Exchange API Reference

Quick reference for `Rs2GrandExchange` — buy, sell, collect, and query GE prices from aScript modules.

## Table of Contents

- [Quick Start](#quick-start)
- [Buy / Sell](#buy--sell)
- [Collect](#collect)
- [Slot Queries](#slot-queries)
- [Offer Details](#offer-details)
- [Price Lookups](#price-lookups)
- [Navigation](#navigation)
- [GrandExchangeRequest Builder](#grandexchangerequest-builder)
- [Enums](#enums)

---

## Quick Start

```java
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
```

**Buy 100 nature runes at 200gp each:**
```java
Rs2GrandExchange.buyItem("Nature rune", 200, 100);
```

**Sell 1 rune platebody at 60k:**
```java
Rs2GrandExchange.sellItem("Rune platebody", 1, 60000);
```

**Collect everything to bank:**
```java
Rs2GrandExchange.collectAll(true);
```

---

## Buy / Sell

### `buyItem(String itemName, int pricePerItem, int quantity)`

Places a buy offer. Returns `true` on success.

- `itemName` — display name (e.g. `"Rune scimitar"`)
- `pricePerItem` — GP per unit
- `quantity` — number of items

Internally builds a `GrandExchangeRequest` with `action=BUY`, `exact=true`.

### `sellItem(String itemName, int quantity, int pricePerItem)`

Places a sell offer from inventory. Returns `true` on success.

- `itemName` — display name of item in inventory
- `quantity` — how many to sell
- `pricePerItem` — GP per unit

### `processOffer(GrandExchangeRequest request)`

Full-featured entry point. Builds the request yourself for control over:

| Field | Type | Description |
|-------|------|-------------|
| `action` | `GrandExchangeAction` | `BUY`, `SELL`, or `COLLECT` |
| `itemName` | `String` | Item name |
| `exact` | `boolean` | Exact name match (default `false` for search) |
| `quantity` | `int` | Number of items |
| `price` | `int` | GP per unit |
| `percent` | `int` | Adjust price by ±N% (e.g. `5`, `-5`, `7`) |
| `slot` | `GrandExchangeSlots` | Target specific slot (optional) |
| `closeAfterCompletion` | `boolean` | Close GE interface after |
| `toBank` | `boolean` | Collect to bank (vs inventory) |

---

## Collect

### `collectAll(boolean toBank)`

Collects all completed offers. Opens GE if needed, deposits inventory if full.

```java
Rs2GrandExchange.collectAll(true);   // to bank
Rs2GrandExchange.collectAll(false);  // to inventory
```

### `collectOffer(GrandExchangeSlots slot, boolean toBank)`

Collects a specific slot. Views the offer, then collects.

```java
Rs2GrandExchange.collectOffer(GrandExchangeSlots.ONE, true);
```

---

## Slot Queries

| Method | Returns | Description |
|--------|---------|-------------|
| `isOpen()` | `boolean` | GE interface is open |
| `isOfferScreenOpen()` | `boolean` | Buy/sell offer screen is showing |
| `getAvailableSlots()` | `GrandExchangeSlots[]` | Empty slots |
| `getAvailableSlotsCount()` | `int` | Number of empty slots |
| `isSlotAvailable(slot)` | `boolean` | Specific slot is empty |
| `isAllSlotsEmpty()` | `boolean` | All slots empty |
| `hasBoughtOffer()` | `boolean` | Any offer in `BOUGHT` state |
| `hasFinishedBuyingOffers()` | `boolean` | Has `BOUGHT` + no `BUYING` |
| `hasFinishedSellingOffers()` | `boolean` | Has `SOLD` + no `SELLING` |
| `getActiveOfferSlots()` | `GrandExchangeSlots[]` | Slots with active offers |
| `getSlotFromIndex(int)` | `GrandExchangeSlots` | Index 0–7 → enum |

**Max slots:** 8 for members, 3 for F2P.

---

## Offer Details

### `getOfferDetails(GrandExchangeSlots slot)`

Returns a `GrandExchangeOfferDetails` object:

```java
GrandExchangeOfferDetails d = Rs2GrandExchange.getOfferDetails(GrandExchangeSlots.ONE);
if (d != null) {
    d.getItemId();           // int — OSRS item ID
    d.getItemName();         // String — resolved via ItemManager
    d.getQuantitySold();     // int
    d.getTotalQuantity();    // int
    d.getPrice();            // int — price per unit set in offer
    d.getSpent();            // int — total GP spent/received
    d.getState();            // GrandExchangeOfferState
    d.isSelling();           // boolean
    d.getSlot();             // GrandExchangeSlots
    d.isCompleted();         // BOUGHT / SOLD / CANCELLED_*
    d.isInProgress();        // BUYING / SELLING
    d.getProgressPercentage(); // int 0–100
}
```

---

## Price Lookups

All hit the **GE Tracker API** (`https://www.ge-tracker.com/api/items/`). Returns `-1` on failure.

| Method | Returns | Description |
|--------|---------|-------------|
| `getPrice(itemId)` | `int` | Overall median price |
| `getSellPrice(itemId)` | `int` | Lowest sell offer (what you'd sell for) |
| `getBuyPrice(itemId)` | `int` | Highest buy offer (what you'd pay) |
| `getBuyingVolume(itemId)` | `int` | Units being bought |
| `getSellingVolume(itemId)` | `int` | Units being sold |

Also available via **OSRS Wiki API** (used internally for caching):
- `WIKI_API_URL` — `prices.runescape.wiki/api/v1/osrs/latest`
- `WIKI_MAPPING_URL` — item name/ID mapping

---

## Navigation

| Method | Description |
|--------|-------------|
| `openExchange()` | Talk to GE Clerk, handle bank pin |
| `closeExchange()` | Close GE interface |
| `backToOverview()` | Go back from offer screen to slots overview |
| `walkToGrandExchange()` | Walker to `BankLocation.GRAND_EXCHANGE` |

---

## GrandExchangeRequest Builder

```java
GrandExchangeRequest request = GrandExchangeRequest.builder()
    .action(GrandExchangeAction.BUY)        // required
    .itemName("Death rune")                 // required
    .exact(true)                            // exact name match
    .price(250)                             // GP per unit
    .quantity(100)                          // count
    .percent(-5)                            // adjust price by -5%
    .slot(GrandExchangeSlots.TWO)           // target specific slot
    .closeAfterCompletion(true)             // close GE after
    .toBank(true)                           // collect to bank
    .build();

Rs2GrandExchange.processOffer(request);
```

---

## Enums

### `GrandExchangeAction`
`BUY` · `SELL` · `COLLECT`

### `GrandExchangeSlots`
`ONE` · `TWO` · `THREE` · `FOUR` · `FIVE` · `SIX` · `SEVEN` · `EIGHT`

### `GrandExchangeOfferState` (from RuneLite API)
`EMPTY` · `BUYING` · `SELLING` · `BOUGHT` · `SOLD` · `CANCELLED_BUY` · `CANCELLED_SELL`

---

## Tips

- **Always check `getAvailableSlotsCount()` before buying** — buying with no slots blocks silently.
- **Use `percent` for faster fills** — `percent: -5` undercuts by 5% for quicker trades.
- **`collectAll(true)` auto-deposits if inventory is full** — safe to call blindly.
- **Price APIs are external** — may return `-1` on network errors; handle gracefully.
- **`buyItem`/`sellItem` are convenience wrappers** — use `processOffer` for full control.
