package com.mysite.sbb.fastapi.optimization;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(
        name = "company_rank_daily",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"stat_date", "company"})
        }
)
@Getter @Setter
public class CompanyRankDaily {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date",nullable = false)
    private LocalDate statDate;

    @Column(nullable = false, length = 64)
    private String company;

    @Column(nullable = false)
    private int cnt;
}
