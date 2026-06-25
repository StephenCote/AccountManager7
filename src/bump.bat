ech off
cd AccountManagerObjects7
call mvn clean install
cd ..\AccountManagerAgent7
call mvn clean install
cd ..\AccountManagerISO42001
call mvn clean install
cd ..\AccountManagerConsole7
call mvn clean package
cd ..\AccountManagerService7
call mvn clean package
