#!/bin/groovy
import groovy.json.*

def exactClone = "EXACT CLONE"
def sourceBranch = "master"
def selectedComponents = []
def sourceEnvDeployments = []
def sourceConfig = null
def targetConfig = null
def targetConfigFile = ""
String ENVIRONMENT_NAMES = "dev,test,pre,prd"

pipeline {
    agent any
    parameters { 

        extendedChoice (description: 'Select source environment', multiSelectDelimiter: ',', 
            name: 'SOURCE_ENVIRONMENT', quoteValue: false, defaultValue: "", saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', 
            value: ENVIRONMENT_NAMES, visibleItemCount: 50)

        extendedChoice (description: 'Select target environment', multiSelectDelimiter: ',', 
            name: 'TARGET_ENVIRONMENT', quoteValue: false, defaultValue: "", saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', 
            value: ENVIRONMENT_NAMES, visibleItemCount: 50)
    }

    stages {

        stage('Select Components') {
            steps {
                script {
                    def sourceConfigFile = "envs/${env.SOURCE_ENVIRONMENT}.json"
                    targetConfigFile = "envs/${env.TARGET_ENVIRONMENT}.json"

                    if (fileExists("${sourceConfigFile}")) {
                        sourceConfig = readJSON file: "${sourceConfigFile}"
                    }
                    else {
                        error "Configuration file for selected source environment(${env.SOURCE_ENVIRONMENT}) is not available"
                    }

                    println "Checkout baseline repo"
                    def baseline = [:]
                    dir("checkoutdir") {
                        def repositoryName = "https://github.com/saurabh-aggarwal-nbs"
                        def baselineRepo = readJSON text: "{'name':'baseline', 'branch': 'main'}"
                        checkoutRepository(baselineRepo)
                        if(fileExists("checkoutdir/${deploymentEnv}-baseline.json")){
                            baseline = []
                            baseline = readJSON file: "${deploymentEnv}-baseline.json"
                        }
                    }
                    println "baseline ${baseline}"

//                    def components = identifyTenantDeployment(desiredDeployment.components, baseline)

                    
                    // process release config to get delta
                    if(baseline.size() > 0)
                    {
                        sourceEnvDeployments = baseline.findAll {it.name}
                    }
                    else {
                        error "Unable to find any deployments tracked in source environment in Apigee"
                    }
                    println sourceEnvDeployments

                    def componentNames = []
                    def values = [exactClone]
                    values.addAll(sourceEnvDeployments.collect { it.name})
                    print "Deployed Items: " 
                    println values

                    timeout(time: 300, unit: 'SECONDS')
                    {
                        componentNames = input(
                        message: 'Please select the components to update on target environment', ok: 'Next',
                        parameters: [
                            extendedChoice(
                            defaultValue: 'All',
                            description: '',
                            multiSelectDelimiter: ',',
                            name: 'COMPONENTS',
                            quoteValue: false,
                            saveJSONParameterToFile: false,
                            type: 'PT_CHECKBOX',
                            value: values.join(','),
                            visibleItemCount: 10)]).split(',')
                    }
                    if (componentNames.contains(exactClone))
                    {
                        selectedComponents = values.findAll { it != exactClone }
                    }
                    else
                    {
                        componentNames.each { componentName ->
                            selectedComponents << componentName
                        }
                    }

                    print "Selected components to copy: "
                    println selectedComponents
                }
            }             
        }

//        stage('Update Components') {
//            steps {
//                script {
//                    // Fetch the changes
//                    selectedComponents.each { componentName ->
//                        def updated = false
//                        def targetComponent = targetConfig.components.find { it.name  == componentName }
//                        def sourceComponent = sourceConfig.components.find { it.name  == componentName }
//                        def trackingEntryName = componentTrackingEntryKey(componentName, sourceConfig.parameters.tenant)
//                        def envEntry = sourceEnvDeployments.find {it.name == trackingEntryName }
//                        def envComponentDeployment = new JsonSlurper().parseText(envEntry.value)
//
//                        if(targetComponent)
//                        {
//                            targetComponent.commit = envComponentDeployment.tag ? "" : envComponentDeployment.commit
//                            targetComponent.tag = envComponentDeployment.tag
//                            targetComponent.branch = envComponentDeployment.tag && !envComponentDeployment.commit &&  (envComponentDeployment.branch !=  "main" && envComponentDeployment.branch !=  "master") ? "master" : envComponentDeployment.branch
//                        }
//                        else
//                        {
//                            targetConfig.components << [
//                                "name": componentName,
//                                "commit": envComponentDeployment.commit ?: "",
//                                "tag": envComponentDeployment.tag ?: "",
//                                "branch": envComponentDeployment.branch ?: "",
//                                "globalConfigKeyFormat": sourceComponent? (sourceComponent.globalConfigKeyFormat ?: "") : "",
//                                "type": sourceComponent? sourceComponent.type : "",
//                                "flowHooksEnabled": false,
//                            ]
//                        }
//                    }
//                }
//            }
//        }

        stage('Update Configuration') {
            steps {
                script {
                    def branch = env.BUILD_TAG
                    sh "ssh-agent bash -c \" \
                    git config --global user.email jenkins@test.com; \
                    git config --global user.name saurabh-aggarwal-nbs; \
                    git add ${finalFile}; \
                    git checkout -b ${branch}; \
                    git add ${finalFile}; \
                    git commit -m 'Coping configuration from ${env.SOURCE_ENVIRONMENT} to ${env.TARGET_ENVIRONMENT}, Copying ${selectedComponents.join(', ')}'; \
                    git status; \
                    git push origin ${branch}\""
                    println "pushing the changes completed"
                }
            }
        }
    }

    post {
        always {
//            cleanWs cleanWhenAborted: false, cleanWhenFailure: false, cleanWhenNotBuilt: false, cleanWhenUnstable: false
        }
        success {
            println "Successful"
        }
        unstable {
            println "Unstable"
        }
        failure {
            println "Failed"
        }
    }
}



def checkoutRepository(repository) {
    def checkoutName = "refs/heads/${repository.branch}"
    if(repository.tag){
        checkoutName = "refs/tags/${repository.tag}"
        println "Checking out from tag ${checkoutName}"
    }
    else if(repository.commit)
    {
        checkoutName = "${repository.commit}"
        println "Checking out from commit ${checkoutName}"
    }
    else
    {
        println "Checking out from branch ${checkoutName}"
    }
    println "Repository name : ${repository.name}"
    def checkoutInfo = checkout([
            $class: 'GitSCM',
            branches: [[name: checkoutName]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [],
            submoduleCfg: [],
            userRemoteConfigs: [[
                                        credentialsId: 'saurabh-aggarwal-nbs',
                                        url: "https://github.com/saurabh-aggarwal-nbs/${repository.name}.git/"
                                ]]
    ])
    return checkoutInfo
}