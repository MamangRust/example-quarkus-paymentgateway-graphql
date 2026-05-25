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
### CREATE WITHDRAW
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation CreateWithdraw($req: CreateWithdrawRequest!) { createWithdraw(req: $req) { withdrawId cardNumber withdrawAmount status } }",
  "variables": {
    "req": {
      "cardNumber": "1234567890123456",
      "withdrawAmount": 80000,
      "withdrawTime": "2026-05-24T12:00:00"
    }
  }
}

HTTP 200

[Captures]
withdraw_id: jsonpath "$.data.createWithdraw.withdrawId"


### =====================================
### GET WITHDRAW BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindWithdraw($withdrawId: BigInteger!) { findWithdrawById(id: $withdrawId) { withdrawId cardNumber withdrawAmount status } }",
  "variables": {
    "withdrawId": {{withdraw_id}}
  }
}

HTTP 200


### =====================================
### GET MONTHLY WITHDRAW AMOUNTS STATS
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query { getMonthlyWithdrawAmounts(year: 2026) { month amount } }"
}

HTTP 200


### =====================================
### TRASH WITHDRAW
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashWithdraw($withdrawId: BigInteger!) { trashWithdraw(id: $withdrawId) { withdrawId } }",
  "variables": {
    "withdrawId": {{withdraw_id}}
  }
}

HTTP 200


### =====================================
### RESTORE WITHDRAW
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreWithdraw($withdrawId: BigInteger!) { restoreWithdraw(id: $withdrawId) { withdrawId } }",
  "variables": {
    "withdrawId": {{withdraw_id}}
  }
}

HTTP 200
