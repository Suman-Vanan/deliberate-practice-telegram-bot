### Do I need a DAO?

- https://stackoverflow.com/questions/15554826/new-to-java-whats-jpa-and-dao
- https://www.infoq.com/news/2007/09/jpa-dao
- https://stackoverflow.com/questions/8550124/what-is-the-difference-between-dao-and-repository-patterns

### Bootstrapping users

> I'm trying to obtain all users of a telegram group, I see the method getChatAdministrators, 
but I think the API doesn't have a method for obtaining all members.

A response:

> What you are trying to archive is currently not possible using the bot api. I'm not sure about the nodejs api, 
but the official bot api (which your implementation is likely to use) currently doesn't allow getting specific information 
about all members.

(https://stackoverflow.com/questions/51050063/get-all-users-of-telegram-group)