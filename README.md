# MinecraftHouses

A simple Spigot plugin that lets players buy or rent houses via signs. House data is stored in `plugins/MinecraftHouses/houses.yml`.

## Building

Requires Maven and Java 8. On Linux/macOS run:

```bash
mvn package
```

The resulting JAR can be found in `target/`.

On Windows you can also use `build.bat` which will place the JAR in a `build` folder:

```cmd
build.bat
```

## Usage

Place a sign with `[House]` or `[Rent]` on the first line and a price on the second. Players can right-click the sign while sneaking to purchase or rent it. Sneaking and right-clicking again sells the house.

Commands:

- `/houses list` – list all houses
- `/houses owned` – list houses you own
- `/houses adddoor <id>` – link a door to a house (admin, right-click the door after running the command)
- `/houses removedoor <id>` – unlink a door from a house (admin, right-click the door after running the command)
- `/houses reload` – reload configuration (admin only)
- `/houses info <id>` – view details about a house
- `/houses trust <id> <player>` – allow a player to open your doors
- `/houses untrust <id> <player>` – revoke door access
- `/houses market` – open a GUI with all houses

In the market GUI use the arrows to move between pages, and the house details
screen includes a Back button to return.

Only the owner of a house can interact with its doors.

## Configuration

By default the plugin stores house data in a local YAML file. To use a MySQL
database set `database.use-mysql` to `true` in `config.yml` and adjust the
`host`, `port`, `user`, `pass` and `database` settings. When MySQL is enabled
data is saved automatically every `database.save-interval` seconds. Set
`database.debug` to `true` to print SQL operations to the console.
