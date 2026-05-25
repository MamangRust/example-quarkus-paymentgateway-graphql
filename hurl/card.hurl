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
### CREATE CARD
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation CreateCard($req: CreateCardRequest!) { createCard(req: $req) { cardId cardNumber cardType cardProvider } }",
  "variables": {
    "req": {
      "userId": 1,
      "cardType": "DEBIT",
      "expireDate": "2030-12-31",
      "cvv": "123",
      "cardProvider": "VISA"
    }
  }
}

HTTP 200

[Captures]
card_id: jsonpath "$.data.createCard.cardId"
card_number: jsonpath "$.data.createCard.cardNumber"


### =====================================
### GET CARD BY ID
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query FindCard($cardId: BigInteger!) { findCardById(id: $cardId) { cardId cardNumber cardType cardProvider } }",
  "variables": {
    "cardId": {{card_id}}
  }
}

HTTP 200


### =====================================
### UPDATE CARD
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation UpdateCard($req: UpdateCardRequest!) { updateCard(req: $req) { cardId cardType cardProvider } }",
  "variables": {
    "req": {
      "cardId": {{card_id}},
      "userId": 1,
      "cardType": "VIRTUAL",
      "expireDate": "2031-06-30",
      "cvv": "999",
      "cardProvider": "MASTERCARD"
    }
  }
}

HTTP 200


### =====================================
### CARD DASHBOARD
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query { getCardDashboard { totalCards activeCards } }"
}

HTTP 200


### =====================================
### MONTHLY CARD BALANCES STATS
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "query { getMonthlyBalances(year: 2026) { month balance } }"
}

HTTP 200


### =====================================
### TRASH CARD
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation TrashCard($cardId: BigInteger!) { trashCard(id: $cardId) { cardId } }",
  "variables": {
    "cardId": {{card_id}}
  }
}

HTTP 200


### =====================================
### RESTORE CARD
### =====================================
POST http://localhost:8080/graphql
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "query": "mutation RestoreCard($cardId: BigInteger!) { restoreCard(id: $cardId) { cardId } }",
  "variables": {
    "cardId": {{card_id}}
  }
}

HTTP 200
