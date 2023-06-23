Setup via API: Add the initial admin credential - note, the credential field is binary, so the value is base64 encoded

curl -X "POST" -H "Content-Type:application/json" -d "{\"model\":\"credential\", \"type\":\"hashed_password\", \"credential\":\"cGFzc3dvcmQ=\"}" http://localhost:8080/AccountManagerService7/rest/setup
