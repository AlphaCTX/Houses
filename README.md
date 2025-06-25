# MinecraftHouses

A simple Spigot plugin that lets players buy or rent houses via signs. House data is stored in `plugins/MinecraftHouses/houses.yml`.

## Building

Requires Maven and Java 8. Run:

```bash
mvn package
```

The resulting JAR can be found in `target/`.

## Usage

Place a sign with `[House]` or `[Rent]` on the first line and a price on the second. Players can right-click the sign while sneaking to purchase or rent it. Sneaking and right-clicking again sells the house.

Commands:

- `/houses list` – list all houses
- `/houses owned` – list houses you own
- `/houses door <add|remove> <id>` – add or remove a door to a house (right-click the door after running the command)
- `/houses reload` – reload configuration (admin only)

Only the owner of a house can interact with its doors.
