package com.mymedroads.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientProfile {

    private String name;
    private String age;
    private String gender;
    private String mobile;
    private String email;
    private String destination;
    private String medicalIssue;
}
