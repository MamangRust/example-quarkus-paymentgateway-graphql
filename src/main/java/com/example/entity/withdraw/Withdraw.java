package com.example.entity.withdraw;

import java.sql.Timestamp;
import java.util.UUID;

import com.example.entity.BaseModel;
import com.example.enums.Status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "withdraws")
public class Withdraw extends BaseModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "withdraw_id")
    public Long withdrawId;

    @Column(name = "withdraw_no", nullable = false, unique = true)
    public UUID withdrawNo;

    @Column(name = "card_number", nullable = false)
    public String cardNumber;

    @Column(name = "withdraw_amount", nullable = false)
    public Integer withdrawAmount;

    @Column(name = "withdraw_time", nullable = false)
    public Timestamp withdrawTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;
}