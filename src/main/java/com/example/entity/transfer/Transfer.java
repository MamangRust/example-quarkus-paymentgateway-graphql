package com.example.entity.transfer;

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
@Table(name = "transfers")
public class Transfer extends BaseModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id")
    public Long transferId;

    @Column(name = "transfer_no", nullable = false, unique = true)
    public UUID transferNo;

    @Column(name = "transfer_from", nullable = false)
    public String transferFrom;

    @Column(name = "transfer_to", nullable = false)
    public String transferTo;

    @Column(name = "transfer_amount", nullable = false)
    public Integer transferAmount;

    @Column(name = "transfer_time", nullable = false)
    public Timestamp transferTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;
}