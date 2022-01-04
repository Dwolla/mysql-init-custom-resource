# MySQL Init CloudFormation Custom Resources

![Dwolla/mysql-init-custom-resource CI](https://github.com/Dwolla/mysql-init-custom-resource/actions/workflows/ci.yml/badge.svg)
[![license](https://img.shields.io/github/license/Dwolla/mysql-init-custom-resource.svg?style=flat-square)]()

CloudFormation Custom Resources for initializing MySQL RDS instances with databases and users, using secrets stored in Secrets Manager.

## Deploying

The Serverless file requires several environment variables be set to control how the Lambda functions are published.

| Environment Variable Name | Description |
|---------------------------|-------------|
|`BUCKET`|S3 bucket where the Zip files containing the code should be stored|
|`ACCOUNT`|AWS Account ID|
|`STAGE`|Logical environment name|
|`SUBNET_ID`|ID of the subnet where the Lambda should run. This indicates the Lambda will be part of a VPC.|

`sbt` will also set `USER_ARTIFACT_PATH` and `DATABASE_ARTIFACT_PATH` with paths to the zip files it creates for
the two Lambdas.

Deploy the whole thing with `sbt deploy`.

## Database Initialization

The database initialization resource takes in `DatabaseName`, `Host`, `Port`,
`MasterDatabaseUsername`, `MasterDatabasePassword`, and `UserConnectionSecret` 
Secrets Manager secret ID parameters, and returns the name of the database it 
created as the Physical Resource ID. It also creates a role for use with the 
database (using the template `{DatabaseName}_role` for the role name) and a 
user with which to connect to the database (using the database name as
the username).

```json
"DatabaseMydb": {
  "Properties": {
    "DatabaseName": "mydb",
    "Host": "database-hostname",
    "Port": "database-port",
    "MasterDatabaseUsername": {
      "Ref": "MasterDatabaseUser"
    },
    "MasterDatabasePassword": {
      "Ref": "MasterDatabasePassword"
    },
    "UserConnectionSecrets": [
      {"Ref": "PasswordForMydbUser1"}
    ],
    "ServiceToken": {
      "Fn::ImportValue": "mysql-init-custom-resource:Stage:InitPostgresDatabaseArn"
    }
  },
  "Type": "AWS::CloudFormation::CustomResource"
},
```

## Example Use

Here's an example of a CloudFormation template using the custom resources:

```json
{
  "AWSTemplateFormatVersion": "2010-09-09",
  "Description": "Example RDS stack showing the use of the database initialization custom resources",
  "Parameters": {
    "MasterDatabasePassword": {
      "NoEcho": true,
      "Type": "String"
    },
    "MasterDatabaseUser": {
      "Default": "root",
      "Type": "String"
    }
  },
  "Resources": {
    "RdsServiceDatabase": {
      "DeletionPolicy": "Retain",
      "Properties": {},
      "Type": "AWS::RDS::DBInstance"
    },
    "DatabaseMydb": {
      "DependsOn": [
        "PasswordForMydbUser1",
        "RdsServiceDatabase"
      ],
      "Properties": {
        "DatabaseName": "mydb",
        "Host": {
          "Fn::GetAtt": [
            "RdsServiceDatabase",
            "Endpoint.Address"
          ]
        },
        "MasterDatabasePassword": {
          "Ref": "MasterDatabasePassword"
        },
        "MasterDatabaseUsername": {
          "Ref": "MasterDatabaseUser"
        },
        "Port": {
          "Fn::GetAtt": [
            "RdsServiceDatabase",
            "Endpoint.Port"
          ]
        },
        "UserConnectionSecrets": [
          {"Ref": "PasswordForMydbUser1"}
        ],
        "ServiceToken": {
          "Fn::ImportValue": "mysql-init-custom-resource:Stage:InitPostgresDatabaseArn"
        }
      },
      "Type": "AWS::CloudFormation::CustomResource"
    },
    "PasswordForMydbUser1": {
      "DependsOn": [
        "RdsServiceDatabase",
        "DatabaseMydb"
      ],
      "Properties": {
        "Description": "Password for mydb/user1",
        "GenerateSecretString": {
          "ExcludeCharacters": "';`",
          "GenerateStringKey": "password",
          "RequireEachIncludedType": "true",
          "SecretStringTemplate": {
            "Fn::Join": [
              "",
              [
                "{\"database\":\"mydb\",\"host\":\"",
                {
                  "Fn::GetAtt": [
                    "RdsServiceDatabase",
                    "Endpoint.Address"
                  ]
                },
                "\",\"port\":",
                {
                  "Fn::GetAtt": [
                    "RdsServiceDatabase",
                    "Endpoint.Port"
                  ]
                },
                ",\"user\":\"user1\"}"
              ]
            ]
          }
        },
        "Name": "databases/mydb/user1"
      },
      "Type": "AWS::SecretsManager::Secret"
    }
  },
  "Outputs": {
    "DatabaseEndpointAddress": {
      "Description": "URI to database",
      "Value": {
        "Fn::GetAtt": [
          "RdsServiceDatabase",
          "Endpoint.Address"
        ]
      }
    },
    "DatabaseEndpointPort": {
      "Description": "Database Port",
      "Value": {
        "Fn::GetAtt": [
          "RdsServiceDatabase",
          "Endpoint.Port"
        ]
      }
    }
  }
}
```
