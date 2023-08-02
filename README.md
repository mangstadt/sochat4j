# SOChat4j

SOChat4j is a general-purpose Java library for interacting with [Stack Overflow Chat](https://chat.stackoverflow.com) and its sister chat sites.

# Requirements

* Java 11
* [Maven](http://maven.apache.org) (for building)

# Example

```java
String email = "email@example.com";
String password = "password";

try (IChatClient client = ChatClient.connect(Site.STACKOVERFLOW, email, password)) {
  IRoom room = client.joinRoom(1);

  room.addEventListener(UserEnteredEvent.class, event -> {
    try {
      room.sendMessage("Welcome, " + event.getUsername() + "!");
    } catch (RoomPermissionException | IOException e) {
      e.printStackTrace();
    }
  });

  room.addEventListener(UserLeftEvent.class, event -> {
    try {
      room.sendMessage("Bye, " + event.getUsername() + "!");
    } catch (RoomPermissionException | IOException e) {
      e.printStackTrace();
    }
  });

  room.sendMessage("WelcomeBot Online!");

  System.out.println("Press Enter to terminate the bot.");
  try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
    reader.readLine();
  }

  room.sendMessage("Leaving, bye!");
} catch (InvalidCredentialsException e) {
  System.err.println("Login credentials invalid.");
} catch (RoomNotFoundException e) {
  System.err.println("Room not found.");
} catch (PrivateRoomException e) {
  System.err.println("Cannot join room because it is private.");
} catch (IOException e) {
  e.printStackTrace();
}
```

[More sample code](https://github.com/mangstadt/sochat4j/tree/master/src/test/java/com/github/mangstadt/sochat4j/sample) 

# Build Instructions

This project uses Maven and adheres to its conventions.

To build the project, run the command below.

`mvn package`

# Questions/Feedback

Please submit an issue on the [issue tracker](https://github.com/mangstadt/sochat4j/issues).
