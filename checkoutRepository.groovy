#!/bin/groovy

def call(repository) {
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