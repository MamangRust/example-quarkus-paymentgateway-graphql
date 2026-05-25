### =====================================
### REGISTER USER
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json

{
  "query": "mutation Register($input: RegisterRequest!) { register(input: $input) { accessToken refreshToken user { userId username email roles } } }",
  "variables": {
    "input": {
      "username": "hurl_test_user",
      "firstname": "Hurl",
      "lastname": "Test",
      "email": "hurl_test@example.com",
      "password": "Password123!"
    }
  }
}

HTTP 200

[Captures]
register_access_token: jsonpath "$.data.register.accessToken"
register_refresh_token: jsonpath "$.data.register.refreshToken"
register_user_id: jsonpath "$.data.register.user.userId"


### =====================================
### LOGIN
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json

{
  "query": "mutation Login($input: LoginRequest!) { login(input: $input) { accessToken refreshToken user { userId username email roles } } }",
  "variables": {
    "input": {
      "username": "hurl_test_user",
      "password": "Password123!"
    }
  }
}

HTTP 200

[Captures]
access_token: jsonpath "$.data.login.accessToken"
refresh_token: jsonpath "$.data.login.refreshToken"
user_id: jsonpath "$.data.login.user.userId"


### =====================================
### REFRESH TOKEN
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json

{
  "query": "mutation RefreshToken($token: String!) { refreshToken(token: $token) { accessToken refreshToken } }",
  "variables": {
    "token": "{{refresh_token}}"
  }
}

HTTP 200

[Captures]
access_token: jsonpath "$.data.refreshToken.accessToken"
refresh_token: jsonpath "$.data.refreshToken.refreshToken"


### =====================================
### GET ALL USERS (ADMIN/STAFF ONLY)
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query { users { userId username firstname lastname email roles } }"
}

HTTP 200


### =====================================
### GET USER BY USERID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query User($userId: BigInteger!) { user(id: $userId) { userId username firstname lastname email roles } }",
  "variables": {
    "userId": {{user_id}}
  }
}

HTTP 200


### =====================================
### GET USER BY USERNAME
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query UserByUsername($username: String!) { userByUsername(username: $username) { userId username firstname lastname email roles } }",
  "variables": {
    "username": "hurl_test_user"
  }
}

HTTP 200


### =====================================
### UPDATE USER
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation UpdateUser($userId: BigInteger!, $firstname: String!, $lastname: String!) { updateUser(id: $userId, firstname: $firstname, lastname: $lastname) { userId username firstname lastname email roles } }",
  "variables": {
    "userId": {{user_id}},
    "firstname": "RenaldyUpdated",
    "lastname": "HidayatUpdated"
  }
}

HTTP 200


### =====================================
### LOGOUT
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation { logout { message success } }"
}

HTTP 200
