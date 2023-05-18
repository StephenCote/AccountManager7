This package/folder contains classes generated from select schema.

The BaseRecord.toConcrete() method will return an instance of a generated class, allowing for direct access to strongly typed getters/setters.

While the PolicyType object is currently used in policy evaluation, this whole set may be dropped because:
   A) generating the schema in the same package as the generator makes a quick hot mess
   B) the whole premise of V7 was to keep the objects generic for maximum flexibility without having to regenerate the class from the model, which was how V5-6 worked