Database Dependencies
* PostgreSQL 17 or higher
* pg_vector

Database Configuration
* Web App:
     * AccountManagerService7/src/main/webapp/META-INF/context.xml
* Console App:
     * AccountManagerConsole7/src/main/resources.properties
     * test.db.user
     * test.db.password
     * test.db.url
* Database Port: 15430
* Database Name: am72db
* Database User: am7user
* Database Password: password
* JDBC Url: jdbc:postgresql://localhost:15430/am72db

Build Dependencies
* OpenJDK 24
* Node 18 or higher

File System Dependencies
* Create a directory to store keys, certs, and streams, eg: C:/Projects/data/am7
* Update the directory value for web and console app
* Web App:
     * AccountManagerService7/src/main/webapp/WEB-INF/web.xml
     * store.path=C:/Projects/data/am7
* Console App:
     * AccountManagerConsole7/src/main/resources.properties
     * app.basePath=C:/Projects/data/am7
       
Build Order
* AccountManagerObjects7
  * mvn install
* AccountManagerConsole7
  * mvn package
  * cd target
  * Run the following to reset the database, create the default organizations, and create an initial user 'steve'
  * java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -addUser -adminPassword password -setup -olio -list

