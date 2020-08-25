#!/usr/bin/env bash

image=fluree/${PWD##*/}

echo "Running in ${image} container..."

docker build --quiet --tag ${image} .
docker run --rm ${image} "$@"
