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
### CREATE TRANSFER
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation CreateTransfer($req: CreateTransferRequest!) { createTransfer(req: $req) { transferId transferFrom transferTo transferAmount status } }",
  "variables": {
    "req": {
      "transferFrom": "1111222233334444",
      "transferTo": "5555666677778888",
      "transferAmount": 65000
    }
  }
}

HTTP 200

[Captures]
transfer_id: jsonpath "$.data.createTransfer.transferId"


### =====================================
### GET TRANSFER BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindTransfer($transferId: BigInteger!) { findTransferById(id: $transferId) { transferId transferFrom transferTo transferAmount status } }",
  "variables": {
    "transferId": {{transfer_id}}
  }
}

HTTP 200


### =====================================
### GET MONTHLY TRANSFER AMOUNTS STATS
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query { getMonthlyTransferAmounts(year: 2026) { month amount } }"
}

HTTP 200


### =====================================
### TRASH TRANSFER
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashTransfer($transferId: BigInteger!) { trashTransfer(id: $transferId) { transferId } }",
  "variables": {
    "transferId": {{transfer_id}}
  }
}

HTTP 200


### =====================================
### RESTORE TRANSFER
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreTransfer($transferId: BigInteger!) { restoreTransfer(id: $transferId) { transferId } }",
  "variables": {
    "transferId": {{transfer_id}}
  }
}

HTTP 200
