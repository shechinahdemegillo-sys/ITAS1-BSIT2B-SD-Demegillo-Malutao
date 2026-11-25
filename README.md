# Disaster Drill Room — Classroom Emergency Drill Chat

Disaster Drill Room is a Java desktop chat application for classroom emergency simulations. It helps a class run a drill over a local network: broadcast drill alerts, confirm that everyone acknowledged them, and practice requesting emergency resources.

| Project information | Details |
|---|---|
| Course / Section | ITAS1 – BSIT 2B SD |
| Project | Network Programming — Java LAN Chat Application |
| Theme | Disaster Drill Room |
| Members | Shechinah Neth P. Demegillo · Charisse B. Malutao |
| GitHub usernames | shechinahdemegillo-sys · charissemalutao1-bot |
| Repository | https://github.com/shechinahdemegillo-sys/ITAS1-BSIT2B-SD-Demegillo-Malutao |
| Final commit | [paste the final commit hash here] |

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Concept Proposal](#2-concept-proposal)
3. [User Problem and Theme](#3-user-problem-and-theme)
4. [Core Chat Features](#4-core-chat-features)
5. [Two Original Features](#5-two-original-features)
6. [Architecture](#6-architecture)
7. [Message Format](#7-message-format)
8. [Commands](#8-commands)
9. [Setup and LAN Instructions](#9-setup-and-lan-instructions)
10. [Screenshots](#10-screenshots)
11. [Testing Checklist](#11-testing-checklist)
12. [Division of Responsibilities](#12-division-of-responsibilities)
13. [Contribution Log](#13-contribution-log)
14. [Credits and AI Disclosure](#14-credits-and-ai-disclosure)
15. [Project Structure](#15-project-structure)

---

## 1. Project Overview

Disaster Drill Room is a Java Swing application designed for teachers and students who run classroom emergency drills. It combines a group chat with drill alerts that must be acknowledged and a shared list of simulated resource requests.

The application uses:

- **Java TCP sockets** (`ServerSocket` and `Socket`) for LAN communication.
- **Java Swing** for the desktop graphical user interface.
- **Concurrent server handlers**, one thread per client, to serve many connected clients.
- **Shared in-memory server state** for online users and alert acknowledgments.
- **No database or external libraries**; data is held in memory while the server is running.

One computer runs the server. Client computers connect using the server computer's IPv4 address and the same TCP port (5000). The assignment requires testing with at least three client instances across two computers.


---

## 2. Concept Proposal

| Item | Description |
|---|---|
| Theme | Disaster Drill Room |
| Target users | Classroom emergency simulations (teachers and students) |
| Proposed solution | A LAN group chat with drill alerts that members must acknowledge, and a shared list of simulated resource requests |
| Proposed features | Drill alerts with acknowledgments; simulated resource requests |
| Approved by | [Instructor name] |
| Date and how approved |  |

---

## 3. User Problem and Theme

| Item | Description |
|---|---|
| Target users | Students and teachers running classroom emergency drills such as fire, earthquake, or evacuation practice |
| User problem | During a drill, the teacher needs to announce the emergency quickly, know that every participant has seen it, and let students practice asking for supplies. Shouting, separate group chats, or paper make this slow and hard to track. |
| Proposed solution | One shared room where an alert reaches everyone at once, each acknowledgment is counted live, and resource requests are visible to the whole group |

---

## 4. Core Chat Features

- Separate server and client applications using `ServerSocket` and `Socket`.
- One thread per client; three or more clients can connect at the same time.
- Server IP address and port are entered in the client.
- Swing interface: connection controls, nickname field, message input, Send button, conversation area, and online user list.
- Group chat: each message is broadcast once to every client, including the sender, with nickname and time.
- Unique, non-blank nicknames (1 to 18 characters, not case-sensitive).
- Commands: `/name <newname>`, `/exit`, and `/help`.
- Join, leave, and rename updates shown on all clients.
- Validation and helpful errors for blank messages, invalid connection details, unavailable servers, and lost connections.
- Sockets and streams are closed on exit; the server keeps running when a client disconnects.
- The window stays responsive during network activity because the connection runs in the background.

---

## 5. Two Original Features

### 5.1 Drill alerts with acknowledgments

1. A user clicks **Broadcast Drill Alert** and writes the announcement.
2. The server creates an ID (for example `ALERT-3`) and broadcasts the alert with the sender and time.
3. Every other client shows a red banner with an **Acknowledge** button and a beep. The alert is also added to the **Drill alerts** list.
4. When a user acknowledges, the server records the name in a set for that alert, so nobody is counted twice.
5. The server broadcasts the new total, and every client updates the count (for example `2/4 ack`).

### 5.2 Resource requests

1. A user clicks **Request Resource**, chooses an item (first-aid kit, drinking water, flashlight, emergency blanket, or other), and adds the location or details.
2. The server adds the requester's name and the time, then broadcasts the request.
3. Every client adds it to the **Resource requests** list and shows a notice in the chat.

---

## 6. Architecture

```
+----------------------+       TCP Socket       +----------------------------+
| Client A             | <--------------------> | Server                     |
| Java Swing GUI       |                        | ServerSocket (port 5000)   |
| Chat / Alerts        |                        |                            |
+----------------------+                        | ClientHandler threads      |
                                                | Shared state:              |
+----------------------+       TCP Socket       | - connected users          |
| Client B             | <--------------------> | - alerts + acknowledgments |
| Java Swing GUI       |                        |                            |
+----------------------+                        | Broadcast updates          |
                                                |                            |
+----------------------+       TCP Socket       |                            |
| Client C             | <--------------------> |                            |
| Java Swing GUI       |                        |                            |
+----------------------+                        +----------------------------+
```

**Message path:** A user action is sent from the client through a TCP socket. The server reads and validates the request, updates shared state when appropriate, and broadcasts the resulting update. The client's listener thread receives the server messages and schedules the GUI changes on the Swing Event Dispatch Thread.

| Component | Responsibility |
|---|---|
| `Server` | Accepts connections and holds the shared state: connected clients, nicknames, and alert acknowledgments |
| `ClientHandler` (inner class of `Server`) | Runs in its own thread for each client and processes that client's commands |
| `Client` | Swing window: sends the user's actions and displays what the server broadcasts |
| `Connector` thread (client) | Opens the socket in the background so the window never freezes |
| `ChatListener` thread (client) | Reads messages from the server and updates the screen with `SwingUtilities.invokeLater` |

---

## 7. Message Format

The application uses line-based text messages. Fields are separated by `|`; user-provided text fields are Base64 encoded before transmission. (Base64 is encoding, not encryption.)

| Direction | Message | Purpose |
|---|---|---|
| Client → Server | `NAME\|nickname` | Join with a nickname, or change it |
| Client → Server | `CHAT\|text` | Send a chat message |
| Client → Server | `ALERT\|text` | Broadcast a drill alert |
| Client → Server | `ACK\|alertId` | Acknowledge a drill alert |
| Client → Server | `RESOURCE\|item\|details` | Request a simulated resource |
| Client → Server | `HELP` | Ask for the command list |
| Client → Server | `QUIT` | Disconnect |
| Server → Client | `NAME_OK\|nickname` | Confirm the nickname |
| Server → Client | `CHAT\|sender\|time\|text` | Deliver a chat message |
| Server → Client | `SYSTEM\|text` | Deliver a system notice |
| Server → Client | `USERS\|...` | Send the online member list |
| Server → Client | `ALERT\|alertId\|sender\|time\|message` | Deliver a drill alert |
| Server → Client | `ACK\|alertId\|name\|total` | Report an acknowledgment and the new total |
| Server → Client | `RESOURCE\|requester\|item\|details\|time` | Deliver a resource request |
| Server → Client | `ERROR\|text` | Report an error |

---

## 8. Commands

| Command | Description |
|---|---|
| `/name <newname>` | Change your nickname, subject to validation and uniqueness. |
| `/exit` | Leave the chat. |
| `/help` | Display the supported commands and usage information. |

Drill alerts, acknowledgments, and resource requests are controlled through the buttons and lists in the GUI.

---

## 9. Setup and LAN Instructions

### 9.1 Requirements

| Requirement | Details |
|---|---|
| Java | JDK [version you used, e.g. 17] or newer |
| IDE | [e.g. VS Code, Apache NetBeans, or another Java IDE; command line is also supported] |
| Dependencies | None (standard Java library only; no Maven or Gradle) |
| Network | All computers on the same LAN or Wi-Fi network |
| Port | TCP 5000 (allowed through the server computer's firewall) |

### 9.2 Compile

Put `Server.java` and `Client.java` in the same folder and run:

```
javac *.java
```

### 9.3 Start the server

```
java Server
```

The console shows `SERVER ONLINE` and `Waiting for clients on port 5000...`.

### 9.4 Start a client

```
java Client
```

1. Enter the **Server IP** (use `127.0.0.1` if the server is on the same computer).
2. Enter the **Port** (`5000`).
3. Enter a **Nickname** (1 to 18 characters).
4. Click **Connect**. The status dot turns green and shows Online.

### 9.5 Connect from another computer (LAN)

1. On the server computer, open Command Prompt and run `ipconfig`. Note the **IPv4 Address** of the Wi-Fi adapter (for example `192.168.1.5`).
2. Allow Java (or inbound TCP port 5000) through the firewall on the server computer.
3. On the other computer, run `java Client`, type the server's IPv4 address, port `5000`, and a nickname, then click Connect.

### 9.6 Troubleshooting

| Problem | Fix |
|---|---|
| "Connection failed" when connecting | Make sure the server is running and the IP and port are correct |
| Works on one computer but not between two | Check that both are on the same Wi-Fi or LAN and that the firewall allows port 5000 |
| Nickname error on connect | The nickname is already in use or longer than 18 characters; choose another |
| Server IP stopped working | The Wi-Fi IP can change; run `ipconfig` again |
| School or public Wi-Fi blocks devices | Use a phone hotspot or a personal router |
| `javac` is not recognized | Install the JDK (not only the JRE) and restart the terminal |

---

## 10. Screenshots

<img width="1911" height="1020" alt="image" src="https://github.com/user-attachments/assets/a75efb28-0f98-42c2-b2ed-03e67e112edc" />
<img width="1920" height="1008" alt="image" src="https://github.com/user-attachments/assets/2c6f71ce-6993-4925-8c1c-bc8f2fd80e34" />


---

## 11. Testing Checklist

Record the actual result of each test after running it. Do not mark a test as passed until it has been demonstrated.

| # | Test | Steps | Expected result | Actual result | Status |
|:---:|---|---|---|:---:|:---:|
| 1 | Three concurrent clients | Connect three clients across two computers. | All clients appear in the online list. | [Record result] | [Pass/Fail] |
| 2 | Message broadcast | Send a message from one client. | All connected clients receive it with sender and time. | [Record result] | [Pass/Fail] |
| 3 | Online list updates | Join and disconnect a client. | The list updates on all remaining clients. | [Record result] | [Pass/Fail] |
| 4 | Nickname change | Use `/name NewName`. | The new nickname appears for all clients. | [Record result] | [Pass/Fail] |
| 5 | Duplicate nickname | Attempt to use a nickname already online. | The server rejects the duplicate. | [Record result] | [Pass/Fail] |
| 6 | Blank message | Attempt to send an empty message. | The blank message is rejected. | [Record result] | [Pass/Fail] |
| 7 | Help command | Use `/help`. | The list of commands is displayed. | [Record result] | [Pass/Fail] |
| 8 | Exit command | Use `/exit`. | The client disconnects and the others see a leave notice. | [Record result] | [Pass/Fail] |
| 9 | Client disconnect | Close one client window. | The server and the remaining clients keep working. | [Record result] | [Pass/Fail] |
| 10 | Server unavailable | Connect while the server is stopped. | A helpful error appears and the window does not freeze. | [Record result] | [Pass/Fail] |
| 11 | Invalid connection details | Leave the IP blank, or type `abc` as the port. | A validation message appears. | [Record result] | [Pass/Fail] |
| 12 | Drill alert broadcast | Broadcast a drill alert from one client. | All other clients show the alert banner and a list entry. | [Record result] | [Pass/Fail] |
| 13 | Alert acknowledgment | Acknowledge the alert from each other client. | The count updates on all clients, and a repeat acknowledgment is refused. | [Record result] | [Pass/Fail] |
| 14 | Resource request | Request a first-aid kit with details. | The request appears in the list on all clients. | [Record result] | [Pass/Fail] |

---

## 12. Division of Responsibilities

| Member | Responsibilities |
|---|---|
| [Full name] Demegillo | [e.g. server code, drill alert feature, documentation] |
| [Full name] Malutao | [e.g. client interface, resource request feature, testing and screenshots] |

---

## 13. Contribution Log

| Date | Member | Contribution | Evidence |
|---|---|---|---|
| [date] | [name] | [e.g. created the repository and uploaded Server.java] | [commit link or hash] |
| [date] | [name] | [e.g. added the resource request feature] | [commit link or hash] |
| [date] | [name] | [e.g. ran the three-client test and uploaded screenshots] | [commit link or hash] |

The commit history of this repository also shows each member's work.

---

## 14. Credits and AI Disclosure

- Language and interface: Java and Java Swing.
- Networking: Java `java.net` socket classes (`ServerSocket` and `Socket`) with `java.io` streams.
- External libraries/assets: None. The project uses only the standard Java library. Update this section if any are added.
- AI assistance: We used an AI assistant (Claude, by Anthropic) to help [...]. The original [...]. [...] Review and edit this statement so it accurately reflects the team's work.

---

## 15. Project Structure

```
ITAS1-BSIT2B-SD-Demegillo-Malutao/
├── Server.java          server: accepts clients, holds shared state, broadcasts
├── Client.java          Swing client: connects, sends actions, shows updates
├── README.md            project documentation
└── docs/
    └── screenshots/     test and demo screenshots used in this README
```
