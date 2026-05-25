package com.example.entity.card;

import java.sql.Date;

import com.example.entity.BaseModel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cards")
public class Card extends BaseModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_id")
    public Long cardId;

    @Column(name = "user_id", nullable = false)
    public Integer userId;

    @Column(name = "card_number", unique = true, nullable = false, length = 16)
    public String cardNumber;

    @Column(name = "card_type", nullable = false)
    public String cardType;

    @Column(name = "expire_date", nullable = false)
    public Date expireDate;

    @Column(nullable = false, length = 3)
    public String cvv;

    @Column(name = "card_provider", nullable = false)
    public String cardProvider;
}