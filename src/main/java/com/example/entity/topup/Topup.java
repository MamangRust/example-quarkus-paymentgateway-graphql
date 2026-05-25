package com.example.entity.topup;

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
@Table(name = "topups")
public class Topup extends BaseModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "topup_id")
    public Long topupId;

    @Column(name = "topup_no", nullable = false, unique = true)
    public UUID topupNo;

    @Column(name = "card_number", nullable = false)
    public String cardNumber;

    @Column(name = "topup_amount", nullable = false)
    public Integer topupAmount;

    @Column(name = "topup_method", nullable = false)
    public String topupMethod;

    @Column(name = "topup_time", nullable = false)
    public Timestamp topupTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;
}
