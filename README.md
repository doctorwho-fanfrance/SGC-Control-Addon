# SGC Control Addon

A Forge 1.20.1 addon for **Just Stargate Mod (JSG)** that adds an SGC-inspired computer terminal for monitoring and controlling Stargates.

> Current release: **0.5.3**

## Features

- SGC-style Stargate control monitor
- Automatic detection of a nearby JSG Stargate
- Planetary Stargate database opened with `TAB`
- Select and dial registered Stargate destinations
- Manual Milky Way glyph entry
- Real-time chevron locking animation
- 7/8/9-chevron dialing support
- Iris control
- Abort / shutdown controls
- Energy and gate status displays
- Incoming wormhole detection
- Large red **OFFWORLD ACTIVATION** alert for incoming connections
- Incoming chevron-status boxes displayed in red
- Persistent display of the real address currently being dialed when reopening the terminal
- Placeable laptop-style control terminal with horizontal orientation
- French and English language files

## Requirements

- Minecraft **1.20.1**
- Minecraft Forge **47.x**
- **Just Stargate Mod (JSG)** for Forge 1.20.1
- Java **17** to build from source

## Controls

| Control | Action |
| --- | --- |
| `TAB` | Open / close the Stargate database |
| `UP` / `DOWN` | Select a database entry |
| `PAGE UP` / `PAGE DOWN` | Move through database pages |
| `ENTER` | Dial the selected destination |
| `K` | Open / close the SGC control keyboard |
| `I` | Toggle the iris |
| `A` or `BACKSPACE` | Abort dialing |
| `C` | Close the active Stargate |
| `R` | Refresh the JSG link and gate data |
| `ESC` | Leave the database / close the screen |

The on-screen control keyboard also provides **LIST**, **DIAL ON/OFF**, **ALERT**, **SHUT OFF**, and **IRIS** controls.

## Building

Use Java 17.

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build
```

The compiled mod will be created in:

```text
build\libs\sgccontrol-0.5.3.jar
```

## Installation

1. Install Forge for Minecraft 1.20.1.
2. Install Just Stargate Mod and its required dependencies.
3. Put `sgccontrol-0.5.3.jar` in the Minecraft `mods` folder.
4. Start Minecraft and place the SGC terminal near a functional JSG Stargate.

## Notes

The addon reads the actual Stargate network provided by JSG. A local Stargate is displayed in the database but cannot dial itself. Remote destinations must exist and be registered by JSG.

Incoming and outgoing connections are distinguished using JSG's real Stargate connection state, so **OFFWORLD ACTIVATION** is reserved for genuine incoming wormholes.

## Credits / reference boundary

This is an unofficial fan-made addon and is not affiliated with MGM, Amazon, the Stargate franchise rights holders, or the Just Stargate Mod developers.

A separate Stargate dial simulator supplied during development was used only as a visual and functional reference. Its SWF files, audio, fonts, graphics and executable resources are **not redistributed** in this project. See [`NOTICE_REFERENCE.md`](NOTICE_REFERENCE.md).

## License

Code in this repository is distributed under the [MIT License](LICENSE), except for third-party trademarks, names, game assets, and intellectual property which remain the property of their respective owners.
