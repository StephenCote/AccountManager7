/// Re-Setup
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -addUser -adminPassword password -setup -olio -list

/// Re-write a person/character statistics and wardrobe
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Kaylinn -update -person "{firstName: \"Laurel\", middleName: \"Kelsey\", lastName: \"Carrera\", name: \"Laurel Kelsey Carrera\", age: 24, hairColor: {id:123}, hairStyle: \"long and tangled\", eyeColor:{id: 291}, alignment:\"CHAOTICGOOD\",race:[\"E\", \"S\"],ethnicity:[\"NINE\"],trades:[\"enchantress\"]}" -statistics "{physicalStrength:7,physicalEndurance:12,manualDexterity:15,agility:17,mentalStrength:18,mentalEndurance:16, intelligence:15,wisdom:17,perception:15,creativity:18,spirituality:18,charisma:19}" -outfit "demi bra,g-string,tank top,mini skirt,sandals,anklet,amulet,jewelry:piercing:7:f:ear"

/// Re-write a person/character statistics and personality
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Zair -update -person "{firstName: \"Duke\", middleName: \"Abraham\", lastName: \"Washington\", name: \"Duke Abraham Washington\", alignment:\"CHAOTICEVIL\",race:[\"E\", \"L\"],trades:[\"serial killer\"]}" -statistics "{physicalStrength:17,agility:17,intelligence:18,perception:19,charisma:12}" -personality "{psychopathy:0.9,narcissism:0.65}"

/// Create a chat configuration between two characters (Now named Duke and Laurel, Duke is character1, used by 'system')
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Duke -character2 Laurel -assist -setting random -scene -prune -rating RC -model fim-local -chatConfig custom.chat

/// Chat with the characters
java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -chatConfig custom.chat -promptConfig custom.prompt -chat

java -jar AccountManagerConsole7-7.0.0-SNAPSHOT.jar -organization /Public -username steve -password password -olio -character1 Flynt -character2 Laurel -assist -rating G -setting "random" -prune -userPromptConfig wonky.chat -interact -chat