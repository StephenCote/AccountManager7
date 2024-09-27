/// Re-Setup
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -addUser -adminPassword password -setup -olio -list

/// Re-write a person/character statistics and wardrobe
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Yareni -update -person "{firstName: \"Laurel\", middleName: \"Kelsey\", lastName: \"Carrera\", name: \"Laurel Kelsey Carrera\", age: 24, hairColor: {id:123}, hairStyle: \"long and tangled\", eyeColor:{id: 291}, alignment:\"CHAOTICGOOD\",race:[\"E\"],ethnicity:[\"NINE\"],trades:[\"shopkeeper\"]}" -statistics "{physicalStrength:7,physicalEndurance:12,manualDexterity:15,agility:17,mentalStrength:18,mentalEndurance:16, intelligence:8,wisdom:10,perception:15,creativity:18,spirituality:10,charisma:19}" -outfit "bra,panties,blouse,skirt,thigh-high heeled boots,anklet,amulet,jewelry:piercing:7:f:ear"

/// Re-write a person/character statistics and personality
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Khymeir -update -person "{firstName: \"Duke\", middleName: \"Abraham\", lastName: \"Washington\", hairStyle: \"short\", name: \"Duke Abraham Washington\", alignment:\"CHAOTICGOOD\",race:[\"E\"],trades:[\"soldier\"]}" -statistics "{physicalStrength:17,agility:17,intelligence:18,perception:19,charisma:12}" -personality "{psychopathy:0.75,narcissism:0.3,machiavellianism:0.75}"

/// Create a chat configuration between two characters (Now named Duke and Laurel, Duke is character1, used by 'system')
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Duke -character2 Laurel -assist -setting random -scene -prune -rating RC -model "mannix/llama3.1-8b-abliterated:q5_k_s" -interact -nlp "user is ${user.asg} named ${user.firstName}" -chatConfig custom.chat

/// (inverse)
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Laurel -character2 Duke -assist -setting random -scene -prune -rating RC -model "mannix/llama3.1-8b-abliterated:q5_k_s" -chatConfig custom2.chat

/// Import a chat configuration
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -promptConfig custom.prompt -import -path ..\clean.prompt.json

/// Chat with the characters
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -chatConfig custom.chat -promptConfig custom.prompt -chat

java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Flynt -character2 Laurel -assist -rating G -setting "random" -prune -userPromptConfig wonky.chat -interact -chat