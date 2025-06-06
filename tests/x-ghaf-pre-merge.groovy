#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'
def DEF_GITHUB_PR_NUMBER = ''

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
  parameters([
    string(name: 'GITHUB_PR_NUMBER', defaultValue: DEF_GITHUB_PR_NUMBER, description: 'Ghaf PR number')
  ])
])

target_jobs = [:]

////////////////////////////////////////////////////////////////////////////////

def targets = [
  // docs
  [ system: "x86_64-linux",
    target: "doc",
    archive: false,
    scs: false,
    hwtest_device: null,
  ],

  // lenovo x1
  [ system: "x86_64-linux",
    target: "lenovo-x1-carbon-gen11-debug",
    archive: true,
    scs: false,
    hwtest_device: "lenovo-x1",
  ],
  [ system: "x86_64-linux",
    target: "lenovo-x1-carbon-gen11-debug-installer",
    archive: true,
    scs: false,
    hwtest_device: null,
  ],

  // nvidia orin
  [ system: "aarch64-linux",
    target: "nvidia-jetson-orin-agx-debug",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx",
  ],
  [ system: "aarch64-linux",
    target: "nvidia-jetson-orin-nx-debug",
    archive: true,
    scs: false,
    hwtest_device: "orin-nx",
  ],
  [ system: "x86_64-linux",
    target: "nvidia-jetson-orin-agx-debug-from-x86_64",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx",
  ],
  [ system: "x86_64-linux",
    target: "nvidia-jetson-orin-nx-debug-from-x86_64",
    archive: true,
    scs: false,
    hwtest_device: "orin-nx",
  ],

  // others
  [ system: "x86_64-linux",
    target: "generic-x86_64-debug",
    archive: true,
    scs: false,
    hwtest_device: "nuc",
  ],
]

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '100'))
  }
  environment {
    // https://stackoverflow.com/questions/46680573
    GITHUB_PR_NUMBER = params.getOrDefault('GITHUB_PR_NUMBER', DEF_GITHUB_PR_NUMBER)
  }
  stages {
    stage('Checkenv') {
      steps {
        sh 'if [ -z "$GITHUB_PR_NUMBER" ]; then exit 1; fi'
        script {
          def href = "${REPO_URL}/pull/${GITHUB_PR_NUMBER}"
          currentBuild.description = "<br>(<a href=\"${href}\">#${GITHUB_PR_NUMBER}</a>)"
        }
      }
    }
    stage('Checkout') {
      steps {
        script { utils = load "utils.groovy" }
        dir(WORKDIR) {
          checkout scmGit(
            userRemoteConfigs: [[
              url: REPO_URL,
              name: 'pr_origin',
              // Below, we set the git remote: 'pr_origin'.
              // We use '/merge' in pr_origin to build the PR as if it was
              // merged to the PR target branch. To build the PR head (without
              // merge) you would replace '/merge' with '/head'.
              refspec: '+refs/pull/${GITHUB_PR_NUMBER}/merge:refs/remotes/pr_origin/pull/${GITHUB_PR_NUMBER}/merge',
            ]],
            branches: [[name: 'pr_origin/pull/${GITHUB_PR_NUMBER}/merge']],
            extensions: [
              [$class: 'WipeWorkspace'],
            ],
          )
          script {
            env.TARGET_REPO = sh(script: 'git remote get-url pr_origin', returnStdout: true).trim()
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }
    stage('Evaluate') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_eval_jobs(targets)
            target_jobs = utils.create_parallel_stages(targets, testset='_boot_bat_')
          }
        }
      }
    }
    stage('Build targets') {
      steps {
        script {
          parallel target_jobs
        }
      }
    }
  }
}
