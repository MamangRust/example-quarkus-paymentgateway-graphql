### =====================================
### PRE-REQ: LOGIN
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json

{
  "query": "mutation Login($input: LoginRequest!) { login(input: $input) { accessToken } }",
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


### =====================================
### CREATE TOPUP
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation CreateTopup($req: CreateTopupRequest!) { createTopup(req: $req) { topupId cardNumber topupAmount topupMethod status } }",
  "variables": {
    "req": {
      "cardNumber": "1234567890123456",
      "topupAmount": 75000,
      "topupMethod": "BANK_TRANSFER"
    }
  }
}

HTTP 200

[Captures]
topup_id: jsonpath "$.data.createTopup.topupId"


### =====================================
### GET TOPUP BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindTopup($topupId: BigInteger!) { findTopupById(id: $topupId) { topupId cardNumber topupAmount status } }",
  "variables": {
    "topupId": {{topup_id}}
  }
}

HTTP 200


### =====================================
### TRASH TOPUP
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashTopup($topupId: BigInteger!) { trashTopup(id: $topupId) { topupId } }",
  "variables": {
    "topupId": {{topup_id}}
  }
}

HTTP 200


### =====================================
### RESTORE TOPUP
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreTopup($topupId: BigInteger!) { restoreTopup(id: $topupId) { topupId } }",
  "variables": {
    "topupId": {{topup_id}}
  }
}

HTTP 200
