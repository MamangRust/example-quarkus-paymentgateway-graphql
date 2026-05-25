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
### CREATE TRANSACTION
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}
X-api-Key: test_merchant_api_key

{
  "query": "mutation CreateTransaction($req: CreateTransactionRequest!) { createTransaction(req: $req) { transactionId amount paymentMethod status } }",
  "variables": {
    "req": {
      "cardNumber": "1234567890123456",
      "amount": 100000,
      "paymentMethod": "CREDIT_CARD",
      "merchantId": 1,
      "transactionTime": "2026-05-24T12:00:00"
    }
  }
}

HTTP 200

[Captures]
transaction_id: jsonpath "$.data.createTransaction.transactionId"


### =====================================
### GET TRANSACTION BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindTransaction($transactionId: BigInteger!) { findTransactionById(id: $transactionId) { transactionId amount paymentMethod status } }",
  "variables": {
    "transactionId": {{transaction_id}}
  }
}

HTTP 200


### =====================================
### GET MONTHLY TRANS AMOUNTS STATS
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query { findMonthlyTransactionAmounts(year: 2026) { month amount } }"
}

HTTP 200


### =====================================
### TRASH TRANSACTION
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashTransaction($transactionId: BigInteger!) { trashTransaction(id: $transactionId) { transactionId } }",
  "variables": {
    "transactionId": {{transaction_id}}
  }
}

HTTP 200


### =====================================
### RESTORE TRANSACTION
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreTransaction($transactionId: BigInteger!) { restoreTransaction(id: $transactionId) { transactionId } }",
  "variables": {
    "transactionId": {{transaction_id}}
  }
}

HTTP 200
